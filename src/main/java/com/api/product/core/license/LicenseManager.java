package com.api.product.core.license;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Offline licence check. A licence is a small JSON document signed by the vendor (Ed25519); the product holds only the public key.
 * Without a licence the product runs as a 30-day trial. An offline licence deters casual sharing, it is not copy protection: anyone
 * who can modify the program can remove the check. States: VALID, EXPIRED, INVALID, TRIAL, TRIAL_EXPIRED.
 */
public final class LicenseManager {

    public static final int TRIAL_DAYS = 30;
    public static final int TRIAL_MAX_PROJECTS = 5;
    private static final ObjectMapper M = new ObjectMapper();

    private LicenseManager() {}

    static Path dir() {
        return Paths.get(System.getProperty("user.home"), ".api-quality-studio");
    }

    static Path licenseFile() {
        String o = System.getProperty("license.file");
        return o != null && !o.isBlank() ? Paths.get(o) : dir().resolve("license.json");
    }

    static Path trialFile() {
        return dir().resolve("trial.start");
    }

    public static final class Status {
        public String state;
        public String customer = "";
        public String edition = "trial";
        public String expires = "";
        public long daysLeft;
        public int maxProjects;
        public String message = "";

        /** Runs, imports and new projects are allowed. */
        public boolean active() {
            return state.equals("VALID") || state.equals("TRIAL");
        }
    }

    public static Status status() {
        Status s = new Status();
        try {
            Path f = licenseFile();
            if (Files.exists(f)) {
                JsonNode doc = M.readTree(Files.readString(f, StandardCharsets.UTF_8));
                String problem = verify(doc);
                if (problem != null) {
                    s.state = "INVALID";
                    s.message = "The licence file is not valid (" + problem + "). Install the licence you received from your vendor.";
                    return s;
                }
                JsonNode p = doc.get("payload");
                s.customer = p.path("customer").asText("");
                s.edition = p.path("edition").asText("standard");
                s.expires = p.path("expires").asText("");
                s.maxProjects = p.path("maxProjects").asInt(0);
                long left = ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(s.expires));
                s.daysLeft = left;
                s.state = left >= 0 ? "VALID" : "EXPIRED";
                s.message = left >= 0 ? "Licensed to " + s.customer + " (" + s.edition + "), valid until " + s.expires + "." : "The licence expired on " + s.expires + ". Running tests and creating projects is paused; reports and exports still work.";
                return s;
            }
        } catch (Exception e) {
            s.state = "INVALID";
            s.message = "The licence file could not be read: " + e.getMessage();
            return s;
        }
        // trial
        try {
            Files.createDirectories(dir());
            if (!Files.exists(trialFile())) Files.writeString(trialFile(), LocalDate.now().toString(), StandardCharsets.UTF_8);
            LocalDate start = LocalDate.parse(Files.readString(trialFile(), StandardCharsets.UTF_8).trim());
            long left = TRIAL_DAYS - ChronoUnit.DAYS.between(start, LocalDate.now());
            s.edition = "trial";
            s.daysLeft = Math.max(0, left);
            s.maxProjects = TRIAL_MAX_PROJECTS;
            s.expires = start.plusDays(TRIAL_DAYS).toString();
            s.state = left > 0 ? "TRIAL" : "TRIAL_EXPIRED";
            s.message = left > 0 ? "Trial: " + left + " day(s) left, up to " + TRIAL_MAX_PROJECTS + " projects." : "The 30-day trial has ended. Install a licence to run tests and create projects; reports and exports still work.";
        } catch (Exception e) {
            s.state = "TRIAL";
            s.daysLeft = TRIAL_DAYS;
            s.maxProjects = TRIAL_MAX_PROJECTS;
            s.message = "Trial (the start date could not be stored).";
        }
        return s;
    }

    /** Verifies and installs a licence. Returns the new status or throws IllegalArgumentException with the reason. */
    public static Status install(String text) throws Exception {
        JsonNode doc;
        try {
            doc = M.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException("This is not a licence file (it should be the JSON text you received).");
        }
        String problem = verify(doc);
        if (problem != null) throw new IllegalArgumentException("Licence rejected: " + problem);
        Files.createDirectories(licenseFile().toAbsolutePath().getParent());
        Files.writeString(licenseFile(), M.writerWithDefaultPrettyPrinter().writeValueAsString(doc), StandardCharsets.UTF_8);
        return status();
    }

    /** null = signature and structure are fine, otherwise the reason. */
    static String verify(JsonNode doc) {
        try {
            if (doc == null || !doc.has("payload") || !doc.has("signature")) return "missing payload or signature";
            PublicKey pk = publicKey();
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(pk);
            sig.update(canonical(doc.get("payload")).getBytes(StandardCharsets.UTF_8));
            if (!sig.verify(Base64.getDecoder().decode(doc.get("signature").asText()))) return "the signature does not match (the file was changed or is not from your vendor)";
            JsonNode p = doc.get("payload");
            if (!p.has("customer") || !p.has("expires")) return "customer or expiry missing";
            LocalDate.parse(p.get("expires").asText());
            return null;
        } catch (Exception e) {
            return "unreadable (" + e.getClass().getSimpleName() + ")";
        }
    }

    static PublicKey publicKey() throws Exception {
        String override = System.getProperty("license.publickey");
        byte[] der;
        if (override != null && !override.isBlank()) {
            der = Base64.getDecoder().decode(Files.readString(Paths.get(override), StandardCharsets.UTF_8).trim());
        } else {
            try (InputStream in = LicenseManager.class.getResourceAsStream("/license/public.key")) {
                if (in == null) throw new IllegalStateException("no public key in this build");
                der = Base64.getDecoder().decode(new String(in.readAllBytes(), StandardCharsets.UTF_8).trim());
            }
        }
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
    }

    /** Stable text of the payload for signing: keys sorted, no whitespace. */
    static String canonical(JsonNode payload) throws Exception {
        Map<String, Object> sorted = new java.util.TreeMap<>();
        payload.fields().forEachRemaining(e -> sorted.put(e.getKey(), M.convertValue(e.getValue(), Object.class)));
        return M.writeValueAsString(new LinkedHashMap<>(sorted));
    }

    public static ObjectNode toJson(Status s) {
        ObjectNode n = M.createObjectNode();
        n.put("state", s.state);
        n.put("customer", s.customer);
        n.put("edition", s.edition);
        n.put("expires", s.expires);
        n.put("daysLeft", s.daysLeft);
        n.put("maxProjects", s.maxProjects);
        n.put("message", s.message);
        n.put("active", s.active());
        return n;
    }
}
