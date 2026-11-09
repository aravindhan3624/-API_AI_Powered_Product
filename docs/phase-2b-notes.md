# Phase 2b — Auth, plug-ins, legacy migration (2026-10-21)

## Delivered
| Area | What | Where |
|---|---|---|
| Auth profiles | `none`, `basic`, `bearer`, `apikey` (header/query), `login` (runs a login endpoint once, extracts the token, applies a header template). Credentials missing/empty -> **BLOCKED** with reason, never a silent send | `ApiModel.AuthProfile`, `ModelRunner.applyAuth` |
| Honest 401/403 | Positive test answered 401/403 and endpoint has no auth configured -> BLOCKED `AUTH_NOT_CONFIGURED` (not a false FAIL) | `ModelRunner.judge` |
| Generator | OpenAPI importer creates auth profiles + uses spec examples; formData/binary -> NOT_EXECUTED with reason; undeclared path params get a sample + warning; `-Dvar.NAME=value` keeps secrets out of models | `OpenApiModelImporter`, `ModelRunner` |
| Encryption plug-in | `hybrid-rsa-aes-gcm` envelope (RSA-OAEP + AES-256-GCM), per-run client key, request encrypt / response decrypt, skip-paths, tampered-encryption negative | `HybridEnvelope`, `ApiModel.Envelope` |
| Browser-like session | Cookie jar per run, XSRF-TOKEN cookie -> `X-XSRF-TOKEN` header, NO_SESSION / tampered-token / tampered-CSRF behaviours, empty `Bearer` headers dropped | `ModelRunner` |
| Placeholders | `${var}`, `${var?}` optional, `${a\|b}` alternatives, `${ENV:X}`, `${NOW:fmt}`, `${DATE:-30:fmt}`, `${RANDOM:n}`, `${UUID}`, `${TXN_ID}`, `${TIMESTAMP}`, `${TODAY}`, `"${json:x}"` raw JSON | `ModelRunner.resolve/lookup` |
| Extraction | JSON paths, first-match-wins alternatives, `$header.Name`, `jsonlist:path:max:offset`, `list.find{it.F=='v'}.TARGET` | `ModelRunner.extract` |
| Human gate | `${OTP}` pauses for a person (popup) or `-Dvar.OTP=`; `-Dotp.dialog.off=true` for unattended runs (-> BLOCKED); nothing is read from responses | `HumanGate` |
| Legacy migration | `ExcelProjectImporter`: Excel test data + payload files + config.properties -> model (secrets become `${ENV:PROJECT_NAME}`; unsupported rules reported as warnings) | `projects/CKYC_XL/model.json` |
| Bug fix | Redactor swallowed the next header when `Bearer` was empty | `Redactor` |

## Equivalence result (CKYC, same DEVHOST, positives)
| | Legacy engine (16-Oct report) | New model runner |
|---|---|---|
| PASS | 93 | **93** |
| FAIL | 29 | **29** |
| SKIP / BLOCKED | 4 | **4** |
| Run=N | 147 | 147 NOT_EXECUTED |
Per-test verdict match: **124 / 126 (98.4 %)**. The 2 differences: TC071 (data-dependent, 400 in the new run) and TC180 (server flaps 500/200).
Progression during the session: 3 -> 69 -> 74 -> 82 -> 93 passes as cookie session, `jsonlist`, `find{}`, alternatives and `DATE` were added. Encrypted login works end to end.

## Commands
```
# import a legacy project
mvn -q test -Dsuite=suites/testng-IMPORT.xml -Dexcel=src/test/resources/API_TestData/CKYC/CKYC_testdata.xlsx -Dproject=CKYC -Dout=projects/CKYC_XL/model.json
# run it (password from your environment; OTP popup off for unattended)
export CKYC_PASSWORD=...   # never put it in the model
mvn -q test -Dsuite=suites/testng-MODEL.xml -Dmodel=projects/CKYC_XL/model.json -Dotp.dialog.off=true
```
Report lands in `reports/CKYC/` next to the legacy reports; `reports/CKYC/index.html` lists both.

## Not done yet
1. **Negatives for imported projects** (legacy `NegativeGenerator` makes ~1,269 negatives: wrong type, missing field, SQLi/XSS, BOLA, 1 MB body...). Next: port as a model-level generator (works from any model), then compare with the legacy negative sheet.
2. 11 Groovy `Validate` rules and 1 complex extract rule skipped (reported in warnings).
3. Test Studio (edit/approve/versions), Ollama assistant, BRD ingestion, Playwright flow capture.
4. Token expiry/refresh for `login` auth profiles; per-test auth override.
5. RNSB / SPCBL / TNSC Excel projects not yet imported through `ExcelProjectImporter` (same command, different `-Dproject`).
