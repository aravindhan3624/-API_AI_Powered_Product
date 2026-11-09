# Phase 1 — Reusable core (first slice) — 2026-10-16

## Baselines recorded (legacy engine, before/alongside new code)
| Suite | Result | Notes |
|---|---|---|
| CKYC (`suites/testng-CKYC.xml`, DEVHOST, positive+negative) | **1363 total · 1181 passed · 178 failed · 4 skipped** | Log: `reports/baseline/ckyc_run.log` (85 MB, mostly encrypted payload dumps). The 178 failures include negatives that exposed real defects; triage is a Phase 2 task. |
| DEMO via new model runner (`projects/DEMO/model.json`) | **7 PASS · 1 FAIL · 1 NOT_EXECUTED** | FAIL = `DEMO_007` forged token -> dummyjson returns **500** (genuine finding, CWE-209). NOT_EXECUTED = DRAFT test, as designed. |
Legacy DEMO runner (`suites/testng-DEMO.xml`) was not re-run; equivalence check is still open.

## Delivered (all new code under `com.api.product.*`; legacy untouched)
| File | Purpose |
|---|---|
| `src/main/java/.../core/model/ApiModel.java` | Normalized model v1 (POJOs for `docs/schemas/api-model.v1.schema.json`) |
| `.../core/engine/ModelRunner.java` | Headless model-driven execution: variables/chaining, mutations, rule-pack judging, assertions, redaction |
| `.../core/engine/RulePack.java` | Loads the existing `api-test-rules.properties` unchanged |
| `.../core/engine/TestResult.java` | Result contract v1 |
| `.../core/engine/Redactor.java` | Masks passwords/tokens/OTP/Bearer in stored evidence |
| `.../core/importer/PostmanModelImporter.java` | Postman v2.1 -> model JSON (no Java generated, scripts reported not run) |
| `src/test/java/com/api/product/ModelRunTest.java`, `ImportPostmanTest.java` + `suites/testng-MODEL.xml`, `testng-IMPORT.xml` | Entry points |
| `projects/DEMO|CKYC|TNSC/model.json` | Project models (DEMO hand-written; CKYC 242 endpoints and TNSC 10 imported) |
| `sample_apis/` | Downloaded Petstore v2/v3 and httpbin specs + catalog of free test APIs |

## How to use
```
# import a Postman collection into a project model (all imported tests are DRAFT)
mvn -q test -Dsuite=suites/testng-IMPORT.xml -Dcollection=postman/X.postman_collection.json -Dout=projects/X/model.json
# run a project model (ACTIVE/APPROVED only; add -Dinclude.draft=true to run DRAFT)
mvn -q test -Dsuite=suites/testng-MODEL.xml -Dmodel=projects/DEMO/model.json [-Denv=DEMO]
```
Results: `reports/model-runs/<project>_<runId>/results.json`.

## Not done yet (carry to next session)
1. Equivalence check: legacy DEMO verdicts vs model DEMO.
2. **Encrypted envelope (`EnvelopeCrypto`) and OTP human gate** are not supported by `ModelRunner` -> CKYC/RNSB/SPCBL model runs need these plug-ins (Phase 3 per plan; may pull forward since CKYC is your test project).
3. Imported CKYC model has unresolved `${api_base_url}`/vars: needs environment variables and login chaining (extract rules).
4. Multipart/formdata bodies unsupported (reported as warnings).
5. `config.properties` plaintext secrets not scrubbed yet.
6. Maven multi-module split not done (kept in `src/main` + `src/test` to stay low-risk).
7. Reports are JSON only; advanced HTML + customizable Excel is the next deliverable.
