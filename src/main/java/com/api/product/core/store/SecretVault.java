package com.api.product.core.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Where credentials live. Models and project files only ever contain ${ENV:NAME} references; the values are resolved here:
 * 1) this session's memory (default: gone when Studio stops), 2) the encrypted local vault (only when the user ticks
 * "remember on this PC"), 3) real process environment variables.
 * The vault file sits in the user's profile (never in the project folder), AES-256-GCM encrypted with a random key kept in a
 * sibling file restricted to the current Windows user. That protects against casual copying of project folders and
 * accidental sharing; it does not protect against someone who can log in as this Windows user.
 */
public final class SecretVault {

    private static final ObjectMapper M = new ObjectMapper();
    private static final Map<String, String> MEM = new ConcurrentHashMap<>();
    private static final Set<String> PERSISTED = ConcurrentHashMap.newKeySet();
    private static final Path DIR = Paths.get(System.getProperty("user.home"), ".api-quality-studio");
    private static final Path FILE = DIR.resolve("vault.dat");
    private static final Path KEY = DIR.resolve("vault.key");
    private static boolean loaded;

    private SecretVault() {}

    /** Session memory, then the persisted vault, then real environment variables. */
    public static String get(String name) {
        load();
        String v = MEM.get(name);
        return v != null ? v : System.getenv(name);
    }

    public static boolean has(String name) {
        String v = get(name);
        return v != null && !v.isEmpty();
    }

    /** @param persist true = also store encrypted on this PC (survives restarts) */
    public static synchronized void put(String name, String value, boolean persist) {
        load();
        if (value == null || value.isEmpty()) return;
        MEM.put(name, value);
        if (persist) {
            PERSISTED.add(name);
            save();
        }
    }

    public static synchronized void remove(String name) {
        load();
        MEM.remove(name);
        if (PERSISTED.remove(name)) save();
    }

    /** Names only - values are never returned. */
    public static Set<String> names() {
        load();
        return Collections.unmodifiableSet(new TreeSet<>(MEM.keySet()));
    }

    public static boolean isPersisted(String name) {
        load();
        return PERSISTED.contains(name);
    }

    // ------------------------------------------------------------------ persistence

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (!Files.exists(FILE) || !Files.exists(KEY)) return;
            byte[] key = Base64.getDecoder().decode(Files.readString(KEY, StandardCharsets.UTF_8).trim());
            byte[] all = Base64.getDecoder().decode(Files.readString(FILE, StandardCharsets.UTF_8).trim());
            byte[] iv = new byte[12];
            System.arraycopy(all, 0, iv, 0, 12);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            Map<String, String> m = M.readValue(c.doFinal(all, 12, all.length - 12), new TypeReference<Map<String, String>>() { });
            MEM.putAll(m);
            PERSISTED.addAll(m.keySet());
        } catch (Exception ignored) {
            // an unreadable vault is treated as empty; the user is asked for the values again
        }
    }

    private static void save() {
        try {
            Files.createDirectories(DIR);
            byte[] key;
            byte[] existing = null;
            if (Files.exists(KEY)) {
                try {
                    existing = Base64.getDecoder().decode(Files.readString(KEY, StandardCharsets.UTF_8).trim());
                } catch (Exception unreadable) {
                    Files.deleteIfExists(KEY);   // a key file with wrong permissions from an older build: start a fresh vault
                    Files.deleteIfExists(FILE);
                }
            }
            if (existing != null) {
                key = existing;
            } else {
                key = new byte[32];
                new SecureRandom().nextBytes(key);
                Files.writeString(KEY, Base64.getEncoder().encodeToString(key), StandardCharsets.UTF_8);
                restrict(KEY);
            }
            Map<String, String> toSave = new LinkedHashMap<>();
            for (String n : PERSISTED) if (MEM.containsKey(n)) toSave.put(n, MEM.get(n));
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(M.writeValueAsBytes(toSave));
            byte[] all = new byte[12 + ct.length];
            System.arraycopy(iv, 0, all, 0, 12);
            System.arraycopy(ct, 0, all, 12, ct.length);
            Files.writeString(FILE, Base64.getEncoder().encodeToString(all), StandardCharsets.UTF_8);
            restrict(FILE);
        } catch (Exception e) {
            throw new IllegalStateException("Could not remember the credentials on this PC (" + e.getClass().getSimpleName() + "). They stay available until Studio is closed.");
        }
    }

    /** Best effort: only the current Windows user may use the file (full control for that user, nobody else). */
    private static void restrict(Path p) {
        try {
            AclFileAttributeView v = Files.getFileAttributeView(p, AclFileAttributeView.class);
            if (v == null) return;
            java.nio.file.attribute.UserPrincipal me;
            try {
                me = p.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
            } catch (IOException e) {
                me = v.getOwner();
            }
            AclEntry e = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(me)
                    .setPermissions(java.util.EnumSet.allOf(AclEntryPermission.class)).build();
            v.setAcl(java.util.List.of(e));
        } catch (IOException | UnsupportedOperationException ignored) {
            // not supported on this file system
        }
    }

    public static String envName(String project, String part) {
        return (project + "_" + part).toUpperCase().replaceAll("[^A-Z0-9]+", "_");
    }
}
