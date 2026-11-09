package com.api.automation.security;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.function.Function;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

import com.api.automation.config.ConfigManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Hybrid RSA-OAEP + AES-256-GCM request/response envelope used by the CKYC web app
 * (same algorithm as its src/utils/crypto.ts). Enabled per project:
 *
 *   CKYC.crypto.envelope=true
 *   CKYC.crypto.server.public.key=-----BEGIN PUBLIC KEY-----\n...\n-----END PUBLIC KEY-----
 *
 * Request :  body {"payload": B64(iv‖ciphertext‖tag)}
 *            header clientsecret     = B64(RSA-OAEP-SHA256(serverPublicKey, aesKey))
 *            header clientpublickey  = B64(SPKI of this run's ephemeral RSA-2048 public key)
 * Response:  {"responseObject"|"payload": B64(iv‖ct‖tag), "responseSecret"|"clientSecret"...: B64(wrapped key)}
 *            (the wrapped key may also come as a response header)
 */
public final class EnvelopeCrypto {

    private static final ObjectMapper M = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int IV_LEN = 12;
    private static final OAEPParameterSpec OAEP_SHA256 = new OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);

    /** One ephemeral key pair per project run (like one browser session). */
    private static volatile KeyPair sessionKeys;

    public static class Envelope {
        public final String body;
        public final String clientSecret;
        public final String clientPublicKey;

        Envelope(String body, String clientSecret, String clientPublicKey) {
            this.body = body;
            this.clientSecret = clientSecret;
            this.clientPublicKey = clientPublicKey;
        }
    }

    private EnvelopeCrypto() {}

    public static boolean enabled() {
        return ConfigManager.getBool("crypto.envelope", false);
    }

    public static synchronized void reset() {
        sessionKeys = null;
    }

    private static synchronized KeyPair keys() throws Exception {
        if (sessionKeys == null) {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048, RANDOM);
            sessionKeys = g.generateKeyPair();
        }
        return sessionKeys;
    }

    /** Encrypts a plain JSON body into the envelope. */
    public static Envelope encrypt(String plainJson) throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256, RANDOM);
        SecretKey aes = kg.generateKey();

        byte[] iv = new byte[IV_LEN];
        RANDOM.nextBytes(iv);
        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(Cipher.ENCRYPT_MODE, aes, new GCMParameterSpec(128, iv));
        byte[] ct = gcm.doFinal(plainJson.getBytes(StandardCharsets.UTF_8));   // ciphertext‖tag

        byte[] packed = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, packed, 0, iv.length);
        System.arraycopy(ct, 0, packed, iv.length, ct.length);

        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
        rsa.init(Cipher.ENCRYPT_MODE, serverPublicKey(), OAEP_SHA256);
        byte[] wrapped = rsa.doFinal(aes.getEncoded());

        String payload = b64(packed);
        return new Envelope(
                M.createObjectNode().put("payload", payload).toString(),
                b64(wrapped),
                b64(keys().getPublic().getEncoded()));
    }

    /** True when the response body looks like an encrypted envelope. */
    public static boolean isEnvelope(String body) {
        JsonNode n = parse(body);
        return n != null && n.isObject() && (n.path("responseObject").isTextual() || n.path("payload").isTextual());
    }

    /**
     * Decrypts an envelope response. Returns the body unchanged when it is not encrypted.
     * @param header response header lookup (the wrapped key may come as a header)
     */
    public static String decrypt(String body, Function<String, String> header) throws Exception {
        JsonNode n = parse(body);
        if (n == null || !n.isObject()) return body;
        String data = n.path("responseObject").isTextual() ? n.path("responseObject").asText()
                : n.path("payload").isTextual() ? n.path("payload").asText() : null;
        if (data == null) return body;

        String wrapped = firstText(n, "responseSecret", "clientSecret", "encryptedKey", "secret");
        if (wrapped == null && header != null) {
            for (String h : new String[]{"responseSecret", "clientSecret", "encryptedKey", "responsesecret", "clientsecret"}) {
                wrapped = header.apply(h);
                if (wrapped != null && !wrapped.isBlank()) break;
            }
        }
        if (wrapped == null || wrapped.isBlank()) {
            throw new IllegalStateException("Encrypted response has no wrapped key (responseSecret / clientSecret)");
        }

        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
        rsa.init(Cipher.DECRYPT_MODE, keys().getPrivate(), OAEP_SHA256);
        byte[] aesKey = rsa.doFinal(unb64(wrapped));

        byte[] packed = unb64(data);
        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, packed, 0, IV_LEN));
        byte[] plain = gcm.doFinal(packed, IV_LEN, packed.length - IV_LEN);
        return new String(plain, StandardCharsets.UTF_8);
    }

    // ════════════════════════════════════════════════════════════════════
    private static PublicKey serverPublicKey() throws Exception {
        String pem = ConfigManager.get("crypto.server.public.key");
        if (pem == null || pem.isBlank()) {
            throw new IllegalStateException("crypto.server.public.key is not set for this project in config.properties");
        }
        String b64 = pem.replace("\\n", "\n").replaceAll("-----[^-]+-----", "").replaceAll("\\s+", "");
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(unb64(b64)));
    }

    private static JsonNode parse(String s) {
        try {
            return s == null || s.isBlank() ? null : M.readTree(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstText(JsonNode n, String... keys) {
        for (String k : keys) {
            JsonNode v = n.get(k);
            if (v != null && v.isTextual() && !v.asText().isBlank()) return v.asText();
        }
        return null;
    }

    private static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    private static byte[] unb64(String s) {
        return Base64.getDecoder().decode(s.trim());
    }
}
