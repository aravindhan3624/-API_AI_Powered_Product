# Phase 5 + 6 checklist (written 2026-11-09)

## Verified today (offline, no external systems)
- `mvn -o -q compile`: exit 0.
- CONTRACT suite: 25 / 25 passed. AI-EVAL: 47 / 47 passed (the older "45" figure is out of date).
- The five legacy files edited on 23 Oct / 2 Nov are byte-identical to `D:\IntelliJ_Projects\API_Automation` (synced copies, not stray edits).

## Phase 5 - real proof on CKYC dev (needs the user)
1. Start Studio (`studio.bat`), open http://localhost:8099/app, select `CKYC_XL`.
2. Set credentials: keep "No sign-in needed"; enter the `apiauto` password under `CKYC_PASSWORD` yourself; tick "Remember on this PC". Never paste it in chat.
3. Run the connection check, then "Run full assessment" (authorisation tick, read the request-count preview). Login negatives are capped at 4.
4. Cancel at once if every call after login says "Token Not Available".
5. If a line fails, send the first failing line with request and response masked (no password, token, OTP or customer data). Fix goes into `ModelRunner` / `RunService`.
Done when: login + OTP step (auto-skipped when `twoFactorEnabled: 0`) + the 1,593 tests complete and the report opens.

## Phase 6 - release hardening
| Item | State | Needs |
|---|---|---|
| Installer, bundled Java runtime, manual PDF, audit export | Built | Rebuild only when asked |
| Optional code signing in `tools/make-dist.ps1` | Added: `-CertThumbprint <thumbprint>`; unsigned otherwise | A code-signing certificate installed in the Windows store, and the Windows SDK (signtool) |
| Guard against shipping a licence private key | Added: the build stops if one is in the package | None |
| Production licence key pair | NOT done. `vendor/` still holds the DEV pair | Generate on a secured machine; keep the private key in a password manager or HSM, never in git or OneDrive; replace `src/main/resources/license/public.key`; rebuild; re-issue licences |
| SSO/LDAP, streaming gRPC, multi-tenant isolation, 3,000+ test table speed, competitor benchmark | NOT done | Separate phases after the proof |
