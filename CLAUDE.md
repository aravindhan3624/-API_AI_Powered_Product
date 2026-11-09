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
