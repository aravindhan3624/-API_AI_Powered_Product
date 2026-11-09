package com.api.product.core.license;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.LocalDate;
import java.util.Base64;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * VENDOR tool - not shipped to customers. Keeps the private signing key; customers only receive the public key inside the product.
 * <pre>
 *   LicenseTool keygen  --dir vendor                      writes license-private.key (SECRET) and license-public.key
 *   LicenseTool issue   --key vendor/license-private.key --customer "Bank X" --edition standard --days 365 [--max-projects 0] --out license.json
 *   LicenseTool show    license.json
 * </pre>
 * After keygen copy license-public.key to src/main/resources/license/public.key and rebuild the product.
 */
public final class LicenseTool {

    private static final ObjectMapper M = new ObjectMapper();

    private LicenseTool() {}

    public static void main(String[] a) throws Exception {
        if (a.length == 0) {
            System.out.println("LicenseTool keygen --dir DIR | issue --key FILE --customer NAME --edition standard|enterprise --days N [--max-projects N] --out FILE | show FILE");
            return;
        }
        switch (a[0]) {
            case "keygen": {
                Path dir = Paths.get(opt(a, "--dir", "vendor"));
                Files.createDirectories(dir);
                KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
                Files.writeString(dir.resolve("license-private.key"), Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()), StandardCharsets.UTF_8);
                Files.writeString(dir.resolve("license-public.key"), Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()), StandardCharsets.UTF_8);
                System.out.println("Wrote " + dir.resolve("license-private.key") + " (KEEP SECRET, never ship it) and " + dir.resolve("license-public.key"));
                break;
            }
            case "issue": {
                PrivateKey pk = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(Files.readString(Paths.get(opt(a, "--key", "")), StandardCharsets.UTF_8).trim())));
                ObjectNode payload = M.createObjectNode();
                payload.put("customer", opt(a, "--customer", ""));
                payload.put("edition", opt(a, "--edition", "standard"));
                payload.put("issued", LocalDate.now().toString());
                payload.put("expires", LocalDate.now().plusDays(Long.parseLong(opt(a, "--days", "365"))).toString());
                payload.put("maxProjects", Integer.parseInt(opt(a, "--max-projects", "0")));
                if (payload.get("customer").asText().isBlank()) throw new IllegalArgumentException("--customer is required");
                Signature sig = Signature.getInstance("Ed25519");
                sig.initSign(pk);
                sig.update(LicenseManager.canonical(payload).getBytes(StandardCharsets.UTF_8));
                ObjectNode doc = M.createObjectNode();
                doc.set("payload", payload);
                doc.put("signature", Base64.getEncoder().encodeToString(sig.sign()));
                Path out = Paths.get(opt(a, "--out", "license.json"));
                Files.writeString(out, M.writerWithDefaultPrettyPrinter().writeValueAsString(doc), StandardCharsets.UTF_8);
                System.out.println("Licence for " + payload.get("customer").asText() + " valid until " + payload.get("expires").asText() + " written to " + out);
                break;
            }
            case "show": {
                System.out.println(Files.readString(Paths.get(a[1]), StandardCharsets.UTF_8));
                System.out.println("verification: " + (LicenseManager.verify(M.readTree(Files.readString(Paths.get(a[1]), StandardCharsets.UTF_8))) == null ? "OK" : "FAILED"));
                break;
            }
            default:
                System.out.println("Unknown command " + a[0]);
        }
    }

    private static String opt(String[] a, String name, String def) {
        for (int i = 0; i < a.length - 1; i++) if (a[i].equals(name)) return a[i + 1];
        return def;
    }
}
