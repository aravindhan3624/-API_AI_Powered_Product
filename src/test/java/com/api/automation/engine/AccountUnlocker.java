package com.api.automation.engine;

import java.util.HashMap;
import java.util.Map;

import com.api.automation.config.ConfigManager;
import com.api.automation.config.ProjectContext;
import com.api.automation.dashboard.LiveDashboard;
import com.api.automation.payloads.ResolvedPayload;
import com.api.automation.utils.GlobalStore;
import com.api.automation.utils.TestDecision;
import com.api.automation.utils.TestOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Optional (unlock.enabled=true, OFF by default): when the test user is locked ("account is locked due to multiple failed login
 * attempts"), unlock it through the application's own maker-checker user management and let the run continue:
 *
 *   1. log in as the MAKER (unlock.maker.user)                      -> POST /user/submitActionRequest  mcAction=UNLOCK for the locked user
 *   2. log in as a CHECKER (unlock.checker.users, tried in order)   -> POST /user/getPendingRequests -> POST /user/approveRequest
 *
 * The maker and the checkers are other users, so a locked user is never asked to unlock itself. Their password is
 * unlock.password, or the project's default password (&lt;PROJECT&gt;.var.password) when that is not set.
 * Nothing is written anywhere else; the passwords are never printed.
 */
public final class AccountUnlocker {

    public static final class Outcome {
        public boolean ok;
        public String message = "";

        static Outcome fail(String m) {
            Outcome o = new Outcome();
            o.message = m;
            return o;
        }
    }

    private static final ObjectMapper M = new ObjectMapper();
    private static final String HEADERS = "Accept:application/json;X-Requested-With:XMLHttpRequest;Authorization:Bearer ${token?};X-CSRF-Token:${csrf_token?}";

    private AccountUnlocker() {}

    public static boolean enabled() {
        return ConfigManager.getBool("unlock.enabled", false);
    }

    /** @param loginRow the sheet row of the login API   @param loginBody its JSON body (userName / password / sessionId)   @param target the locked login id */
    public static Outcome unlock(Map<String, String> loginRow, String loginBody, String target) {
        String maker = ConfigManager.get("unlock.maker.user", "").trim();
        String[] checkers = ConfigManager.get("unlock.checker.users", "").split(",");
        String password = ConfigManager.get("unlock.password", "").trim();
        if (password.isEmpty()) password = ConfigManager.get(ProjectContext.require() + ".var.password", "");
        if (maker.isEmpty() || checkers[0].isBlank()) {
            return Outcome.fail("unlock.maker.user and unlock.checker.users are not set in config.properties");
        }
        if (password.isEmpty()) return Outcome.fail("no password for the maker / checker (unlock.password or <PROJECT>.var.password)");

        String savedUser = GlobalStore.get("loginId"), savedPass = GlobalStore.get("password"), savedUserId = GlobalStore.get("userId");
        try {
            say("Account '" + target + "' is locked - trying to unlock it through maker '" + maker + "' and a checker");
            // ── 1. maker submits the request ──
            if (!login(loginRow, loginBody, maker, password)) return Outcome.fail("the maker '" + maker + "' could not log in");
            String makerId = nz(GlobalStore.get("userId"));
            String targetId = ConfigManager.get("unlock.target.userId", "").trim();
            if (targetId.isEmpty()) targetId = findUserId(target);
            if (targetId.isEmpty()) return Outcome.fail("could not find the user id of '" + target + "' in the user list (set unlock.target.userId)");

            ObjectNode req = M.createObjectNode();
            req.put("requestID", "REQ-UNLOCK-" + System.currentTimeMillis());
            req.put("mcModule", "USER");
            req.put("mcAction", "UNLOCK");
            req.put("mcEntityId", targetId);
            req.put("mcEntityName", target);
            req.put("userId", makerId);
            String[] sub = call("/user/submitActionRequest", req.toString());
            if (!accepted(sub)) return Outcome.fail("the unlock request was not accepted: " + brief(sub));
            say("Unlock request submitted for '" + target + "' (user id " + targetId + ")");

            // ── 2. a checker approves it ──
            String lastWhy = "no checker approved it";
            for (String c : checkers) {
                String checker = c.trim();
                if (checker.isEmpty()) continue;
                if (!login(loginRow, loginBody, checker, password)) {
                    lastWhy = "checker '" + checker + "' could not log in";
                    continue;
                }
                String checkerId = nz(GlobalStore.get("userId"));
                ObjectNode pend = M.createObjectNode();
                pend.put("requestID", "REQ-UNLOCK-" + System.currentTimeMillis());
                pend.put("mcModule", "");
                pend.put("mcStatus", "PENDING");
                String[] list = call("/user/getPendingRequests", pend.toString());
                String mcId = findRequestId(list[1], targetId, target);
                if (mcId.isEmpty()) {
                    lastWhy = "checker '" + checker + "' sees no pending unlock request for '" + target + "'";
                    continue;
                }
                ObjectNode ap = M.createObjectNode();
                ap.put("requestID", "REQ-UNLOCK-" + System.currentTimeMillis());
                ap.put("mcId", mcId);
                ap.put("userId", checkerId);
                String[] done = call("/user/approveRequest", ap.toString());
                if (accepted(done)) {
                    say("Unlock request approved by checker '" + checker + "' - '" + target + "' is unlocked");
                    Outcome o = new Outcome();
                    o.ok = true;
                    o.message = "unlocked by maker '" + maker + "' / checker '" + checker + "'";
                    try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return o;
                }
                lastWhy = "checker '" + checker + "' could not approve: " + brief(done);
            }
            return Outcome.fail(lastWhy);
        } catch (Exception e) {
            return Outcome.fail("unlock failed: " + e.getMessage());
        } finally {
            // leave no maker / checker session behind: the real login starts clean
            com.api.automation.security.SessionFilter.reset();
            GlobalStore.remove("token");
            GlobalStore.remove("csrf_token");
            restore("loginId", savedUser);
            restore("password", savedPass);
            restore("userId", savedUserId);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────
    private static boolean login(Map<String, String> loginRow, String loginBody, String user, String password) throws Exception {
        com.api.automation.security.SessionFilter.reset();
        GlobalStore.remove("token");
        GlobalStore.remove("csrf_token");
        ObjectNode body = (ObjectNode) M.readTree(loginBody);
        body.put("userName", user);
        body.put("password", password);
        body.put("sessionId", 0);
        Map<String, String> row = new HashMap<>(loginRow);
        Map<String, String> rr = new HashMap<>();
        ResolvedPayload p = ResolvedPayload.negative("UL", "UNLOCK_LOGIN", "Login of '" + user + "' for the unlock",
                "log in as " + user, "Login Successful", body.toString()).asVerifyPositive();
        TestDecision d = ExecutionEngine.executeSingle(row, p, 0, rr);
        boolean ok = d.outcome == TestOutcome.PASS && GlobalStore.has("token");
        if (!ok) {
            // a second try that takes over an existing session of the same user
            body.put("sessionId", 1);
            p = ResolvedPayload.negative("UL", "UNLOCK_LOGIN", "Login of '" + user + "' for the unlock (take over)",
                    "log in as " + user, "Login Successful", body.toString()).asVerifyPositive();
            d = ExecutionEngine.executeSingle(row, p, 0, new HashMap<>());
            ok = d.outcome == TestOutcome.PASS && GlobalStore.has("token");
        }
        say((ok ? "Logged in as '" : "Could not log in as '") + user + "'");
        return ok;
    }

    private static String[] call(String path, String body) {
        return ExecutionEngine.callRaw("POST", "${api_base_url}${api_subpath}" + path, body, HEADERS);
    }

    private static String findUserId(String loginId) throws Exception {
        ObjectNode b = M.createObjectNode();
        b.put("requestID", "REQ-UNLOCK-" + System.currentTimeMillis());
        b.put("userId", nz(GlobalStore.get("userId")));
        b.putNull("personTypeId");
        b.putNull("searchText");
        String[] r = call("/user/getAllUsers", b.toString());
        JsonNode root = parse(r[1]);
        if (root == null) return "";
        return findInObjects(root, loginId);
    }

    private static final String[] NAME_KEYS = {"loginId", "loginName", "userLoginId", "userName", "username", "uM_LoginId"};
    private static final String[] ID_KEYS = {"userId", "uM_ID", "id", "umId"};

    private static String findInObjects(JsonNode n, String loginId) {
        if (n.isObject()) {
            for (String k : NAME_KEYS) {
                if (n.has(k) && loginId.equalsIgnoreCase(n.get(k).asText(""))) {
                    for (String ik : ID_KEYS) if (n.has(ik) && !n.get(ik).asText("").isBlank()) return n.get(ik).asText();
                }
            }
            for (JsonNode c : n) {
                String f = findInObjects(c, loginId);
                if (!f.isEmpty()) return f;
            }
        } else if (n.isArray()) {
            for (JsonNode c : n) {
                String f = findInObjects(c, loginId);
                if (!f.isEmpty()) return f;
            }
        }
        return "";
    }

    /** id of the pending UNLOCK request of the target user */
    private static String findRequestId(String body, String targetId, String targetLogin) {
        JsonNode root = parse(body);
        return root == null ? "" : findRequest(root, targetId, targetLogin);
    }

    private static String findRequest(JsonNode n, String targetId, String targetLogin) {
        if (n.isObject()) {
            boolean unlock = "UNLOCK".equalsIgnoreCase(n.path("mcAction").asText(""));
            boolean mine = targetId.equals(n.path("mcEntityId").asText(""))
                    || targetLogin.equalsIgnoreCase(n.path("mcEntityName").asText(""));
            if (unlock && mine) {
                for (String k : new String[]{"mcId", "id", "requestId"}) {
                    if (n.has(k) && !n.get(k).asText("").isBlank()) return n.get(k).asText();
                }
            }
            for (JsonNode c : n) {
                String f = findRequest(c, targetId, targetLogin);
                if (!f.isEmpty()) return f;
            }
        } else if (n.isArray()) {
            for (JsonNode c : n) {
                String f = findRequest(c, targetId, targetLogin);
                if (!f.isEmpty()) return f;
            }
        }
        return "";
    }

    private static boolean accepted(String[] r) {
        int http;
        try { http = Integer.parseInt(r[0]); } catch (NumberFormatException e) { return false; }
        if (http >= 400) return false;
        JsonNode n = parse(r[1]);
        if (n == null) return true;
        int sc = n.path("statusCode").asInt(200);
        return sc < 400;
    }

    private static String brief(String[] r) {
        JsonNode n = parse(r[1]);
        String msg = n == null ? "" : n.path("message").asText("");
        return "HTTP " + r[0] + (msg.isEmpty() ? "" : " - " + msg);
    }

    private static JsonNode parse(String s) {
        try {
            return s == null || s.isBlank() ? null : M.readTree(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static void restore(String key, String value) {
        if (value == null) GlobalStore.remove(key); else GlobalStore.put(key, value);
    }

    private static void say(String m) {
        System.out.println("🔓 " + m);
        LiveDashboard.log("WARN", null, "🔓 " + m);
    }
}
