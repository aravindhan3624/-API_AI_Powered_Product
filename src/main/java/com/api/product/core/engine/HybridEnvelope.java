package com.api.product.core.engine;

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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Envelope plug-in "hybrid-rsa-aes-gcm": body {"payload": B64(iv|ciphertext|tag)} + headers
 * clientsecret (RSA-OAEP-SHA256 wrapped AES-256 key) and clientpublickey (this run's ephemeral RSA public key).
 * One instance per run (= one browser session). Independent re-implementation: the engine never imports legacy code.
 */
public final class HybridEnvelope {

    private static final ObjectMapper M = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int IV_LEN = 12;
    private static final OAEPParameterSpec OAEP = new OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);

    private final PublicKey serverKey;
    private KeyPair clientKeys;

    public static final class Wire {
        public final String body;
        public final String clientSecret;
        public final String clientPublicKey;

        Wire(String body, String clientSecret, String clientPublicKey) {
            this.body = body;
            this.clientSecret = clientSecret;
            this.clientPublicKey = clientPublicKey;
        }
    }

    public HybridEnvelope(String serverPublicKeyPem) throws Exception {
        String b64 = serverPublicKeyPem.replace("\\n", "\n").replaceAll("-----[^-]+-----", "").replaceAll("\\s+", "");
        this.serverKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64)));
    }

    private synchronized KeyPair keys() throws Exception {
        if (clientKeys == null) {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048, RANDOM);
            clientKeys = g.generateKeyPair();
        }
        return clientKeys;
    }

    public Wire encrypt(String plainJson) throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256, RANDOM);
        SecretKey aes = kg.generateKey();
        byte[] iv = new byte[IV_LEN];
        RANDOM.nextBytes(iv);
        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(Cipher.ENCRYPT_MODE, aes, new GCMParameterSpec(128, iv));
        byte[] ct = gcm.doFinal(plainJson.getBytes(StandardCharsets.UTF_8));
        byte[] packed = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, packed, 0, iv.length);
        System.arraycopy(ct, 0, packed, iv.length, ct.length);

        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
        rsa.init(Cipher.ENCRYPT_MODE, serverKey, OAEP);
        byte[] wrapped = rsa.doFinal(aes.getEncoded());
        return new Wire(M.createObjectNode().put("payload", b64(packed)).toString(), b64(wrapped),
                b64(keys().getPublic().getEncoded()));
    }

    public static boolean isEnvelope(String body) {
        JsonNode n = parse(body);
        return n != null && n.isObject() && (n.path("responseObject").isTextual() || n.path("payload").isTextual());
    }

    public String decrypt(String body, Function<String, String> header) throws Exception {
        JsonNode n = parse(body);
        if (n == null || !n.isObject()) return body;
        String data = n.path("responseObject").isTextual() ? n.path("responseObject").asText()
                : n.path("payload").isTextual() ? n.path("payload").asText() : null;
        if (data == null) return body;
        String wrapped = firstText(n, "responseSecret", "clientSecret", "encryptedKey", "secret");
        if (wrapped == null && header != null) {
            for (String h : new String[] { "responseSecret", "clientSecret", "encryptedKey", "responsesecret", "clientsecret" }) {
                wrapped = header.apply(h);
                if (wrapped != null && !wrapped.isBlank()) break;
            }
        }
        if (wrapped == null || wrapped.isBlank()) throw new IllegalStateException("Encrypted response has no wrapped key");
        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
        rsa.init(Cipher.DECRYPT_MODE, keys().getPrivate(), OAEP);
        byte[] aesKey = rsa.doFinal(Base64.getDecoder().decode(wrapped.trim()));
        byte[] packed = Base64.getDecoder().decode(data.trim());
        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, packed, 0, IV_LEN));
        return new String(gcm.doFinal(packed, IV_LEN, packed.length - IV_LEN), StandardCharsets.UTF_8);
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
}
