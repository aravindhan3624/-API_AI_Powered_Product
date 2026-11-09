package com.api.product.ai;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.api.product.core.model.ApiModel;
import com.api.product.core.model.ApiModel.Endpoint;

/**
 * Reads a BRD / flow document (plain text) and maps it to the project's endpoints, without any model:
 * numbered steps, explicit "POST /path" mentions, fuzzy name matching, business-rule sentences, coverage gaps.
 * The output is a proposal for a human; nothing is changed automatically.
 */
public final class FlowAnalyzer {

    public static final class Step {
        public int no;
        public String text;
        public String method;
        public String path;
        public String endpointId;
        public String endpointName;
        public double confidence;
        public boolean needsPrevious;
    }

    public static final class Analysis {
        public List<Step> steps = new ArrayList<>();
        public List<String> unmatchedPaths = new ArrayList<>();
        public List<String> uncoveredEndpoints = new ArrayList<>();
        public List<String> ruleCandidates = new ArrayList<>();
        public int matched;
    }

    private static final Pattern STEP = Pattern.compile("^\\s*(?:step\\s*\\d+\\s*[:.\\-)]?|\\d+\\s*[.)]|[-*\\u2022])\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern API = Pattern.compile("\\b(GET|POST|PUT|PATCH|DELETE)\\s+((?:https?://[^\\s/]+)?/[A-Za-z0-9_\\-./{}:$]+)");
    private static final Pattern PATH_ONLY = Pattern.compile("(?<![A-Za-z0-9])(/[A-Za-z][A-Za-z0-9_\\-]*(?:/[A-Za-z0-9_\\-{}:$]+)+)");
    private static final Pattern RULE = Pattern.compile("(?i)\\b(must|shall|should|mandatory|required|only if|at least|at most|maximum|minimum|cannot|can not|not allowed|is allowed|are allowed|only an? |valid for|expires?|within \\d+|no more than)\\b");
    private static final Pattern DEPENDS = Pattern.compile("(?i)\\b(using the|from the previous|returned|received|obtained|token|otp|session|reference id|the id)\\b");
    private static final Set<String> GENERIC = Set.of("api", "call", "calls", "the", "user", "system", "send", "request", "response",
            "step", "then", "after", "customer", "should", "will", "and", "for", "with", "from", "into", "that", "this", "details", "data");

    private FlowAnalyzer() {}

    private static final Pattern NUMBERED_HEADING = Pattern.compile("^\\d+(?:\\.\\d+)*\\.?\\s+[A-Z][^.!?\\t]{2,70}$");
    private static final Pattern KNOWN_HEADING = Pattern.compile("(?i)(document control|purpose|scope|environment|catalogue|catalog|field rules|data dictionary|business rules|error handling|security|roles|permissions|test data|non-functional|priority|acceptance|assumption|open question|appendix|how the product)");
    private static final Pattern FLOW_SECTION = Pattern.compile("(?i)(flow|step|journey|scenario|use case|process|workflow|sequence)");
    private static final Pattern RULE_SECTION = Pattern.compile("(?i)(rule|requirement|constraint|security|validation|error|restriction|policy)");
    private static final Pattern RULE_ID = Pattern.compile("(?i)^\\s*(?:BR|REQ|RULE|R)[-_ ]?\\d+\\b.*");

    /**
     * Reads plain text of a BRD. Word/PDF structure is recognised: numbered section headings, table rows (API catalogue
     * rows count as mentions, not as flow steps), rule ids (BR-001). Documents without headings (plain notes) are read line by line.
     */
    public static Analysis analyze(String text, ApiModel model) {
        Analysis a = new Analysis();
        Set<String> usedEndpoints = new HashSet<>();
        Set<String> unmatched = new LinkedHashSet<>();
        String[] lines = text.split("\\R");
        int headings = 0;
        for (String l : lines) {
            String t = l.trim();
            if (NUMBERED_HEADING.matcher(t).matches() && KNOWN_HEADING.matcher(t).find()) headings++;
        }
        boolean sectioned = headings >= 3;
        String section = "";
        int no = 0;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (raw.indexOf('\t') >= 0) {            // table row: API mentions only
                Matcher tm = API.matcher(line);
                while (tm.find()) {
                    String path = tm.group(2).replaceAll("[.,;:)]+$", "");
                    Endpoint hit = find(model, tm.group(1), path);
                    if (hit != null) usedEndpoints.add(hit.id);
                    else unmatched.add(tm.group(1) + " " + path);
                }
                continue;
            }
            if (sectioned && line.length() <= 90 && NUMBERED_HEADING.matcher(line).matches() && !API.matcher(line).find()) {
                boolean inFlowNow = FLOW_SECTION.matcher(section).find();
                if (!inFlowNow || KNOWN_HEADING.matcher(line).find()) {
                    section = line.toLowerCase(Locale.ROOT);
                    continue;
                }
            }
            if (line.length() < 8) continue;
            boolean inFlow = !sectioned || FLOW_SECTION.matcher(section).find();
            boolean inRules = !sectioned || RULE_SECTION.matcher(section).find() || RULE_ID.matcher(line).matches();
            Matcher sm = STEP.matcher(line);
            Matcher am = API.matcher(line);
            boolean explicit = am.find();
            boolean template = line.matches(".*<[^>]+>.*") || line.toLowerCase(Locale.ROOT).contains("the assistant");   // blank placeholders / template hints
            if (inRules && !template && RULE.matcher(line).find() && line.length() < 400) a.ruleCandidates.add(line.replaceFirst("^[-*\u2022]\\s*", ""));
            if (!inFlow) continue;
            boolean bullet = line.matches("^\\s*[-*\u2022]\\s+.*");
            boolean numbered = line.matches("(?i)^\\s*(step\\s*\\d+|\\d+\\s*[.)]).*");
            if (!numbered && !explicit && !(bullet && PATH_ONLY.matcher(line).find())) continue;
            Step s = new Step();
            s.no = ++no;
            s.text = sm.matches() ? sm.group(1).trim() : line;
            s.needsPrevious = DEPENDS.matcher(line).find() && no > 1;
            if (explicit) {
                s.method = am.group(1);
                s.path = am.group(2).replaceAll("[.,;:)]+$", "");
            } else {
                Matcher pm = PATH_ONLY.matcher(line);
                if (pm.find()) s.path = pm.group(1).replaceAll("[.,;:)]+$", "");
            }
            Endpoint best = null;
            double conf = 0;
            if (s.path != null) {
                best = find(model, s.method, s.path);
                if (best != null) conf = (s.method != null && s.method.equals(best.method)) ? 1.0 : 0.9;
                else unmatched.add((s.method == null ? "" : s.method + " ") + s.path);
            }
            if (best == null && s.path == null) {
                Set<String> st = tokens(s.text);
                for (Endpoint ep : model.endpoints) {
                    Set<String> et = tokens((ep.name == null ? "" : ep.name) + " " + (ep.path == null ? "" : ep.path.replaceAll("[^A-Za-z0-9]+", " ")) + " " + ep.id);
                    Set<String> inter = new HashSet<>(st);
                    inter.retainAll(et);
                    if (inter.size() < 2 || st.isEmpty() || et.isEmpty()) continue;
                    double c = inter.size() / Math.sqrt((double) st.size() * et.size());
                    if (c >= 0.34 && c > conf) {
                        best = ep;
                        conf = Math.min(0.8, c);
                    }
                }
            }
            if (best != null) {
                s.endpointId = best.id;
                s.endpointName = best.name;
                s.confidence = Math.round(conf * 100) / 100.0;
                usedEndpoints.add(best.id);
                a.matched++;
            }
            a.steps.add(s);
        }
        a.unmatchedPaths.addAll(unmatched);
        for (Endpoint ep : model.endpoints) if (!usedEndpoints.contains(ep.id)) a.uncoveredEndpoints.add(ep.id);
        return a;
    }

    private static Endpoint find(ApiModel model, String method, String path) {
        String np = norm(path);
        Endpoint best = null;
        for (Endpoint ep : model.endpoints) {
            String ne = norm(ep.path == null ? "" : ep.path);
            if (ne.equals(np) || ne.endsWith(np) || np.endsWith(ne)) {
                if (method != null && method.equals(ep.method)) return ep;
                if (best == null) best = ep;
            }
        }
        return best;
    }

    /** Lower-case path without host, query and ids so /customers/123 equals /customers/{id}. */
    static String norm(String p) {
        String s = p.toLowerCase(Locale.ROOT).replaceAll("^https?://[^/]+", "");
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        s = s.replaceAll("\\$\\{[^}]*}|\\{[^}]*}|:[a-z_]+|/\\d+", "/{}");
        s = s.replaceAll("/+", "/");
        return s.endsWith("/") && s.length() > 1 ? s.substring(0, s.length() - 1) : s;
    }

    private static Set<String> tokens(String s) {
        Set<String> out = new HashSet<>();
        for (String t : s.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (t.length() < 3 || GENERIC.contains(t)) continue;
            out.add(t.length() > 4 && t.endsWith("s") ? t.substring(0, t.length() - 1) : t);
        }
        return out;
    }
}
