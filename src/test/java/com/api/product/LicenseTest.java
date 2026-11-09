package com.api.product;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.api.product.core.license.LicenseManager;
import com.api.product.core.license.LicenseTool;

/** Offline licence checks with a throw-away key pair (the real vendor key is never used in tests). */
public class LicenseTest {

    private Path dir;

    @BeforeClass
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("lic-test");
        LicenseTool.main(new String[] { "keygen", "--dir", dir.toString() });
        System.setProperty("license.publickey", dir.resolve("license-public.key").toString());
        System.setProperty("license.file", dir.resolve("license.json").toString());
    }

    @AfterClass
    public void tearDown() {
        System.clearProperty("license.publickey");
        System.clearProperty("license.file");
    }

    private String issue(String customer, int days) throws Exception {
        Path out = dir.resolve("issued.json");
        LicenseTool.main(new String[] { "issue", "--key", dir.resolve("license-private.key").toString(), "--customer", customer, "--days", String.valueOf(days), "--max-projects", "3", "--out", out.toString() });
        return Files.readString(out, StandardCharsets.UTF_8);
    }

    @Test
    public void validTamperedAndExpiredLicences() throws Exception {
        String good = issue("Test Bank", 30);
        LicenseManager.Status st = LicenseManager.install(good);
        Assert.assertEquals(st.state, "VALID");
        Assert.assertEquals(st.customer, "Test Bank");
        Assert.assertEquals(st.maxProjects, 3);
        Assert.assertTrue(st.active());

        // changing any value after signing must be rejected
        String tampered = good.replace("\"maxProjects\" : 3", "\"maxProjects\" : 0");
        Assert.assertNotEquals(tampered, good);
        try {
            LicenseManager.install(tampered);
            Assert.fail("tampered licence accepted");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("signature"), expected.getMessage());
        }
        Assert.assertEquals(LicenseManager.status().customer, "Test Bank", "the good licence stays installed");

        // a licence signed by another key is rejected
        Path other = Files.createTempDirectory("lic-other");
        LicenseTool.main(new String[] { "keygen", "--dir", other.toString() });
        Path forged = other.resolve("forged.json");
        LicenseTool.main(new String[] { "issue", "--key", other.resolve("license-private.key").toString(), "--customer", "Forger", "--days", "999", "--out", forged.toString() });
        try {
            LicenseManager.install(Files.readString(forged, StandardCharsets.UTF_8));
            Assert.fail("forged licence accepted");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("not from your vendor") || expected.getMessage().contains("signature"));
        }

        // an expired licence is recognised and not active
        LicenseManager.install(issue("Old Bank", -1));
        LicenseManager.Status ex = LicenseManager.status();
        Assert.assertEquals(ex.state, "EXPIRED");
        Assert.assertFalse(ex.active());
    }

    @Test
    public void notALicenceIsExplained() {
        try {
            LicenseManager.install("hello");
            Assert.fail();
        } catch (Exception e) {
            Assert.assertTrue(e.getMessage().contains("not a licence file"));
        }
        Assert.assertNotNull(Paths.get("."));
    }
}
