package com.api.product;

import java.nio.file.Files;
import java.nio.file.Path;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.api.product.studio.TeamAuth;

/** Offline checks of team-mode accounts, sessions, lockout and the role matrix. */
public class TeamAuthTest {

    @Test
    public void accountsSessionsLockoutAndRoles() throws Exception {
        Path f = Files.createTempDirectory("team").resolve("users.json");
        TeamAuth a = new TeamAuth(f);
        String pw = a.bootstrapAdmin();
        Assert.assertNotNull(pw);
        Assert.assertNull(a.bootstrapAdmin(), "only the first start creates an admin");
        Assert.assertFalse(Files.readString(f).contains(pw), "the password is never stored");

        String token = a.login("admin", pw);
        Assert.assertEquals(a.session(token).role, "admin");
        a.add("tina", "Tester-pass-123", "tester");
        a.add("vik", "Viewer-pass-123", "viewer");
        try {
            a.add("bad", "short", "viewer");
            Assert.fail("weak password accepted");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            a.setEnabled("admin", false);
            Assert.fail("last admin disabled");
        } catch (IllegalArgumentException expected) {
            // ok
        }

        for (int i = 0; i < 5; i++) {
            try {
                a.login("vik", "wrong" + i);
            } catch (IllegalArgumentException ignored) {
                // expected
            }
        }
        try {
            a.login("vik", "Viewer-pass-123");
            Assert.fail("a locked account accepted the right password");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("Too many"));
        }

        String t = a.login("tina", "Tester-pass-123");
        Assert.assertEquals(a.session(t).role, "tester");
        a.setEnabled("tina", false);
        Assert.assertNull(a.session(t), "disabling a user ends their sessions");
        a.logout(token);
        Assert.assertNull(a.session(token));

        Assert.assertTrue(TeamAuth.allowed("admin", "POST", "/api/users"));
        Assert.assertFalse(TeamAuth.allowed("tester", "GET", "/api/users"));
        Assert.assertTrue(TeamAuth.allowed("tester", "POST", "/api/project/x/run"));
        Assert.assertFalse(TeamAuth.allowed("tester", "POST", "/api/license"));
        Assert.assertTrue(TeamAuth.allowed("viewer", "GET", "/api/project/x/overview"));
        Assert.assertFalse(TeamAuth.allowed("viewer", "POST", "/api/project/x/run"));
        Assert.assertFalse(TeamAuth.allowed("viewer", "POST", "/api/check"));
    }
}
