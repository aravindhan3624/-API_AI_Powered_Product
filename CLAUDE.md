# CLAUDE.md - API Quality Studio (API_Automation_Prod)

Offline commercial API test studio (Java 17, Maven). Read `Today_Backup_2026-10-29/` first (7 docs) and `HANDOVER.md`.

## Rules
- No online token/API key at runtime. NEVER start Ollama or any model on this PC (remote host only; tests use a stub).
- The user types credentials/OTP; never read, print or store secrets (`projects/*/model.json`, `vendor/`, vault, config.properties).
- Cap login negatives (shared accounts get locked). Do not rebuild `dist/` zip unless asked.
- UI fixes: apply to both this project and legacy `D:\IntelliJ_Projects\API_Automation` when asked.
- Be concise, work phase-wise, verify by running (only when the user allows), report honestly.

## Map
`src/main/java/com/api/product/{ai,capture,studio,core/*}`, `src/main/resources/studio/*.html`, `suites/*.xml`, `docs/`, `tools/make-dist.ps1`.
Run: Studio at http://localhost:8099 (`StudioMain`). Tests: `mvn test -Dsuite=suites/testng-CONTRACT.xml` (25) and `suites/testng-AI-EVAL.xml` (47).

## GitHub remote
- `origin` = https://github.com/aravindhan3624/-API_AI_Powered_Product (public; note the leading hyphen in the name). Branch `main`, first pushed 2026-11-09 (commit 10b8aa7, 248 files).
- Push from `D:\IntelliJ_Projects\API_Automation_Prod` (working root). The OneDrive folder holds a copy of `.git`; do not commit from both. The user signs in to GitHub in the Terminal panel; never ask for a password or token.
- Kept local only (in `.gitignore`): `projects/` (clear-text credentials), `vendor/` (licence private key), `config.properties`, `src/test/resources/{API_TestData,payloads}`, `postman/`, `reports/`, `Today_Backup_*/`, `backup/`, `copilot-profile/`, `target/`, `dist/`, IDE files, `*.xlsx`, `*.zip`, `*.jar`, logs.
