# Phase 2 — Reports + OpenAPI import (slice 1) — 2026-10-16

## Delivered
| Item | Where |
|---|---|
| OpenAPI 3.x / Swagger 2.0 (JSON) importer: endpoints, modules from tags, schema-based sample bodies, contract-based expected 2xx/3xx, deterministic candidates (POSITIVE, MISSING_FIELD x3, INVALID_TYPE, NO_SESSION) | `core/importer/OpenApiModelImporter.java` |
| Auto-detect Postman vs OpenAPI on import; `-Dlifecycle=ACTIVE|DRAFT`, `-Dbaseurl=` override | `ImportPostmanTest` (`suites/testng-IMPORT.xml`) |
| Project-wise report folder `reports/<project>/Reports_<project>_<dd-MMM-yy>_<HH-mm>.html` + `.xlsx` | `core/report/ReportGenerator.java` |
| Single-file offline HTML: summary cards, pass-rate basis shown, outcome bar, module-wise accordions (failing modules first), **search by endpoint/API name/test id/base URL/API URL/method/status/category**, outcome/category/module filters, per-test drawer (base URL, API URL, expected vs actual, remark, OWASP, redacted request/response), dark mode, print | `src/main/resources/report/report-template.html` |
| Clickable history page with View report / Excel buttons (also lists legacy console reports in the same folder) | `report/index-template.html` -> `reports/<project>/index.html` |
| Excel: Summary, Modules, All tests, Failures, Security (choose with `-Dreport.sheets=Summary,Modules,Failures`) | `ReportGenerator` |
| Runner: module, API name, method, base URL, full URL in results; request headers in evidence; 15s connect / 25s read timeouts; unsupported body types -> NOT_EXECUTED with reason | `ModelRunner`, `TestResult` |

## Verified by running (public APIs)
| Project | Endpoints / tests | Result |
|---|---|---|
| DEMO (dummyjson) | 5 / 9 | 7 PASS, 1 FAIL (500 on forged token — real), 1 NOT_EXECUTED |
| PETSTORE2 (Swagger 2) | 20 / 38 | 17 PASS, 17 FAIL, 4 NOT_EXECUTED (formData unsupported) |
| PETSTORE3 (OpenAPI 3) | 19 / 37 | first run 15 PASS, 22 FAIL (before the formData fix) |
| HTTPBIN (52 paths) | 73 / 75 | 50 PASS, 21 FAIL, 2 NOT_EXECUTED |
HTML opened in the built-in browser: cards, module list, search (URL fragment) and detail drawer confirmed working.

## Honest notes on the FAILs
- NO_SESSION FAIL on Petstore = spec declares `api_key` but server accepts anonymous calls (a real contract-vs-behaviour finding).
- INVALID_TYPE -> HTTP 500 = server error on bad input (real).
- Remaining httpbin FAILs are mostly generator limits: endpoints needing specific credentials/values (basic-auth, `/status/{codes}`, redirects, `/response-headers`). Next improvement: use examples from the spec, auth profiles from the project, and mark "needs credentials" as BLOCKED instead of FAIL.

## Open (next sessions)
1. YAML specs (needs jackson-dataformat-yaml dependency), multipart/formData, binary bodies.
2. Auth profiles + login chaining in the importer (OAuth2, basic, API key from secret ref).
3. Test Studio: edit/approve tests (lifecycle UI), regenerate without losing edits.
4. Envelope + OTP plug-ins to run CKYC through the model runner and compare with legacy 1363/1181/178/4.
5. Console: "View Report" button wiring in the live dashboard, Reports history in the product UI.
6. Local LLM adapter (Ollama) + BRD ingestion + web-flow capture (Playwright).
7. User manual PDF (reminder scheduled).

## Commands
```
mvn -q test -Dsuite=suites/testng-IMPORT.xml -Dsource=sample_apis/specs/httpbin_swagger.json -Dout=projects/HTTPBIN/model.json -Dlifecycle=ACTIVE
mvn -q test -Dsuite=suites/testng-MODEL.xml -Dmodel=projects/HTTPBIN/model.json
```
Then open `reports/httpbin.org/index.html`.
