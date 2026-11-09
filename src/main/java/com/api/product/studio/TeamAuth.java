package com.api.product.studio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Team mode: sign-in, roles and per-user audit for a Studio that several people reach over the network. Off by default (single user on
 * localhost needs none of this). Passwords are stored only as salted PBKDF2-HMAC-SHA256 hashes. Sessions are random 256-bit tokens in an
 * HttpOnly, SameSite=Strict cookie with idle and absolute timeouts. Repeated wrong passwords lock the account for a few minutes.
 * Roles: admin (everything, manages users and the licence), tester (everything on projects), viewer (read only).
 */
public final class TeamAuth {

    public static final class User {
        public String username;
        public String salt;
        public String hash;
        public String role = "viewer";
        public boolean enabled = true;
        public String created;
    }

    public static final class Session {
        public final String user;
        public final String role;
        public final long created = System.currentTimeMillis();
        public volatile long lastSeen = System.currentTimeMillis();

        Session(String user, String role) {
            this.user = user;
            this.role = role;
        }
    }

    private static final ObjectMapper M = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final int ITER = 210_000;
    private static final long IDLE_MS = 30 * 60_000L, ABSOLUTE_MS = 12 * 3_600_000L, LOCK_MS = 5 * 60_000L;
    private static final int MAX_FAILS = 5;
    private static final SecureRandom RND = new SecureRandom();

    private final Path file;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, int[]> fails = new ConcurrentHashMap<>();       // user -> {count}
    private final Map<String, Long> lockedUntil = new ConcurrentHashMap<>();
    private List<User> users = new java.util.ArrayList<>();

    public TeamAuth(Path file) {
        this.file = file;
        load();
    }

    public static Path defaultFile() {
        String o = System.getProperty("studio.users");
        return o != null && !o.isBlank() ? Paths.get(o) : Paths.get(System.getProperty("user.home"), ".api-quality-studio", "users.json");
    }

    // ------------------------------------------------------------------ users

    private synchronized void load() {
        try {
            if (Files.exists(file)) users = M.readValue(file.toFile(), new TypeReference<List<User>>() { });
        } catch (IOException e) {
            users = new java.util.ArrayList<>();
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            M.writeValue(file.toFile(), users);
        } catch (IOException e) {
            throw new IllegalStateException("Could not save the users: " + e.getMessage());
        }
    }

    /** First start in team mode: creates the admin with a random one-time password and returns it (null when users already exist). */
    public synchronized String bootstrapAdmin() {
        if (!users.isEmpty()) return null;
        byte[] b = new byte[12];
        RND.nextBytes(b);
        String pw = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        add("admin", pw, "admin");
        return pw;
    }

    public synchronized void add(String username, String password, String role) {
        if (!username.matches("[A-Za-z0-9._-]{3,40}")) throw new IllegalArgumentException("User name: 3-40 letters, digits, . _ -");
        if (!List.of("admin", "tester", "viewer").contains(role)) throw new IllegalArgumentException("Role must be admin, tester or viewer");
        checkPassword(password);
        if (find(username) != null) throw new IllegalArgumentException("User " + username + " already exists");
        User u = new User();
        u.username = username;
        u.role = role;
        u.created = Instant.now().toString();
        setPassword(u, password);
        users.add(u);
        save();
    }

    public synchronized void setPasswordFor(String username, String password) {
        User u = find(username);
        if (u == null) throw new IllegalArgumentException("Unknown user");
        checkPassword(password);
        setPassword(u, password);
        save();
        sessions.entrySet().removeIf(e -> e.getValue().user.equals(username));
    }

    public synchronized void setEnabled(String username, boolean enabled) {
        User u = find(username);
        if (u == null) throw new IllegalArgumentException("Unknown user");
        if (!enabled && u.role.equals("admin") && users.stream().filter(x -> x.role.equals("admin") && x.enabled).count() <= 1)
            throw new IllegalArgumentException("The last active admin cannot be disabled");
        u.enabled = enabled;
        save();
        if (!enabled) sessions.entrySet().removeIf(e -> e.getValue().user.equals(username));
    }

    public synchronized void setRole(String username, String role) {
        User u = find(username);
        if (u == null) throw new IllegalArgumentException("Unknown user");
        if (!List.of("admin", "tester", "viewer").contains(role)) throw new IllegalArgumentException("Role must be admin, tester or viewer");
        if (u.role.equals("admin") && !role.equals("admin") && users.stream().filter(x -> x.role.equals("admin") && x.enabled).count() <= 1)
            throw new IllegalArgumentException("The last active admin cannot lose the admin role");
        u.role = role;
        save();
        sessions.entrySet().removeIf(e -> e.getValue().user.equals(username));
    }

    public synchronized List<Map<String, Object>> listUsers() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (User u : users) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("username", u.username);
            m.put("role", u.role);
            m.put("enabled", u.enabled);
            m.put("created", u.created);
            out.add(m);
        }
        return out;
    }

    private User find(String name) {
        for (User u : users) if (u.username.equalsIgnoreCase(name)) return u;
        return null;
    }

    private static void checkPassword(String pw) {
        if (pw == null || pw.length() < 10) throw new IllegalArgumentException("The password needs at least 10 characters");
    }

    private static void setPassword(User u, String pw) {
        byte[] salt = new byte[16];
        RND.nextBytes(salt);
        u.salt = Base64.getEncoder().encodeToString(salt);
        u.hash = Base64.getEncoder().encodeToString(pbkdf2(pw, salt));
    }

    private static byte[] pbkdf2(String pw, byte[] salt) {
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(pw.toCharArray(), salt, ITER, 256)).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ sessions

    /** Returns a new session token, or throws IllegalArgumentException with a message that does not reveal which part was wrong. */
    public String login(String username, String password) {
        String key = username == null ? "" : username.toLowerCase();
        Long until = lockedUntil.get(key);
        if (until != null && until > System.currentTimeMillis()) throw new IllegalArgumentException("Too many wrong passwords. Try again in a few minutes.");
        User u;
        synchronized (this) {
            u = find(key);
        }
        boolean ok = false;
        if (u != null && u.enabled && password != null) {
            ok = MessageDigest.isEqual(Base64.getDecoder().decode(u.hash), pbkdf2(password, Base64.getDecoder().decode(u.salt)));
        } else {
            pbkdf2(String.valueOf(password), new byte[16]);   // similar cost for unknown users
        }
        if (!ok) {
            int[] c = fails.computeIfAbsent(key, k -> new int[1]);
            if (++c[0] >= MAX_FAILS) {
                lockedUntil.put(key, System.currentTimeMillis() + LOCK_MS);
                c[0] = 0;
            }
            throw new IllegalArgumentException("Wrong user name or password");
        }
        fails.remove(key);
        byte[] t = new byte[32];
        RND.nextBytes(t);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(t);
        sessions.put(token, new Session(u.username, u.role));
        return token;
    }

    public Session session(String token) {
        if (token == null) return null;
        Session s = sessions.get(token);
        if (s == null) return null;
        long now = System.currentTimeMillis();
        if (now - s.lastSeen > IDLE_MS || now - s.created > ABSOLUTE_MS) {
            sessions.remove(token);
            return null;
        }
        s.lastSeen = now;
        return s;
    }

    public void logout(String token) {
        if (token != null) sessions.remove(token);
    }

    /** Sign-ins and user administration go to a separate log next to the users file (they belong to no project). */
    public void audit(String user, String note) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file.toAbsolutePath().getParent().resolve("team-audit.jsonl"),
                    M.createObjectNode().put("ts", Instant.now().toString()).put("user", user == null ? "" : user).put("note", note).toString() + "\n",
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // auditing must never break the action
        }
    }

    /** Role check for one request. */
    public static boolean allowed(String role, String method, String path) {
        switch (role) {
            case "admin":
                return true;
            case "tester":
                return !(path.startsWith("/api/users") || (path.equals("/api/license") && !"GET".equals(method)));
            default:   // viewer: read only; the assistant may be asked, but nothing may be sent to a target or changed
                if ("GET".equals(method)) return !path.startsWith("/api/users");
                return path.equals("/api/chat") || path.equals("/api/logout") || path.equals("/api/me/password");
        }
    }
}
