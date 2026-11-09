# Phase 0 — Audit & Freeze (static audit, 2026-10-16)

**Scope:** read-only audit of the repository + frozen schemas. **No code changed. No suite was executed** (see §6).
Evidence: method outlines of `ExecutionEngine`, `BaseApiRunner`, `PostmanImporter`, `NegativeGenerator`, `FailureClassifier`, `PayloadResolver`, `RequestBuilder`, `ApiPage`, `config.properties` (secrets masked), `api-test-rules.properties`, TestNG suites.

## 1. Current execution flow (as found)

```
suites/testng-<P>.xml → runners/<P>_Runner (extends BaseApiRunner)
  BaseApiRunner.getData(): Excel rows (ExcelUtils/TestDataCatalog) + payload files (PayloadResolver)
     → NegativeGenerator.generate(row, positive)   // adds negative variants per row
     → queue(...) → TestNG @Test
  ExecutionEngine: build request (RequestBuilder, ${CFG}/${ENV} placeholders, SessionFilter, EnvelopeCrypto)
     → send() (Rest Assured) → ApiPage = PageRegistry.find(project, endpoint).validate(response)
     → evaluateNegative()/TestDecision using api-test-rules.properties → FailureClassifier
     → ResponseStore + LiveDashboard (RunView/TestView) + Allure + report generation
  OTP: otp/* (dialog + service) pauses the run for a human
  Optional: ai/AiVerifier (Ollama/OpenAI-compatible, advisory)
```

## 2. Reuse / gap table

| Component | Lines | Verdict | Phase 1+ action |
|---|---|---|---|
| `engine/ExecutionEngine` | 1078 | **Reuse, extract.** Mixes request sending, OTP handling, negative judging, tracing, UI updates in static methods. | Extract `send`, judging, OTP into `core-engine` behind interfaces; keep static facade for legacy. |
| `runners/BaseApiRunner` | 520 | **Reuse as legacy driver.** TestNG-bound, Excel-bound. | Leave; add `ModelRunner` beside it. |
| `pages/*` (`ApiPage`, per-project `*Page`, `PageRegistry`) | ~45 files | **The gap.** One Java class per endpoint = per-project code. | Replace with declarative assertions in the model; keep as legacy oracle. |
| `tools/PostmanImporter` | 502 | **Reuse, retarget.** Emits Excel + **generates a runner class** (`writeRunner`). | Emit normalized model JSON; stop generating Java. |
| `negative/NegativeGenerator` | 430 | **Reuse.** Rich (dates, XML, login) but works on row/payload text, tied to `ResolvedPayload`. | Wrap as `core-generate` generator; add schema-driven mutations. |
| `rules/api-test-rules.properties` + `TestDecision`/`Checks` | – | **Keep — crown jewel.** Already expect/why/OWASP per category. | Version it as rule pack; per-project overrides. |
| `report/FailureClassifier` | 146 | **Reuse** as triage input. | Expose as service; feed Failure Triage agent. |
| `ai/AiVerifier` | 211 | **Reuse** as seed of `LlmClient` SPI. Contains banking-specific system prompt. | Generalise; prompt to registry. |
| `utils/PiiMasker`, `security/*` | – | **Reuse** as redaction/envelope plug-ins. | Move to `core-engine`. |
| `dashboard/LiveDashboard` + `dashboard.html` | 475 | **Evolve.** In-process, no persistence. | Add result-contract JSON feed in Phase 1; DB in Phase 3. |
| `config.properties` | – | **Risk.** Holds real UAT bank hosts and credential-like keys in plaintext, committed. | Phase 1: secrets → env/secret refs; scrub before any sharing. |
| `src/main` | empty | Everything is under `src/test`. | Create `core-*` modules; keep legacy in tests. |

## 3. Risks found
1. **Plaintext secrets / real hostnames** in `config.properties` (masked here). Not product-shippable; also must never reach an LLM prompt.
2. **Static global state** (`ExecutionEngine`, `ProjectContext`, `GlobalStore`) → blocks parallel/multi-tenant workers. Needs per-run context object.
3. **UI coupling** inside engine (`setUrl(TestView…)`, dialogs) → engine not headless-safe.
4. **Code generation by importer** (`writeRunner`) defeats "no Java per project".
5. Per-endpoint `ApiPage` classes encode expectations that must be captured as data without losing fidelity (verify per page in Phase 1).
6. AI system prompt hard-codes "banking KYC/OTP".

## 4. Frozen contracts
- `docs/schemas/api-model.v1.schema.json` — normalized API model + test cases.
- `docs/schemas/result.v1.schema.json` — execution result contract.
Both versioned `1.0`; changes need an ADR note. Legacy-verdict mapping: `PASS`, `PASS_WITH_REMARK`→`PASS`+`remark`, `FAIL`, `BLOCKED`, `SKIPPED`, `NOT_EXECUTED`.

## 5. Decisions recorded (ADR-lite)
| ID | Decision |
|---|---|
| D1–D7 | As in `Product_Architecture_and_Phase_Plan.md` §1 |
| D8 | Product assistant LLM = provider SPI; **no Claude subscription tokens involved**; Ollama default, API key optional. |
| D9 | Web-app inspection = local Playwright capture (deterministic), LLM only for interpretation; credentials via secret ref, never in prompts. |
| D10 | Migration order of legacy projects: DEMO → TNSC → RNSB/SPCBL/CKYC (OTP + envelope last). |

## 6. Baseline run — NOT done (needs your go-ahead)
Suites hit real UAT hosts (RNSB, SPCBL, TNSC, CKYC), trigger OTPs/SMS/email and use credentials. Proposed safe baseline:
1. **DEMO** (public `dummyjson.com`) — run now on request: `mvn -q test -DsuiteXmlFile=suites/testng-DEMO.xml`.
2. Bank suites only when you confirm the environment is UAT-approved, OTP handling is attended, and which suite(s) to run. Record pass/fail/blocked counts into `docs/baseline.md`.

## 7. Phase 1 checklist (for approval)
1. Create Maven modules `core-model`, `core-engine` (keep `legacy` in current tree).
2. Implement model classes + JSON-schema validation from the two frozen schemas.
3. `ModelRunner`: model → Rest Assured call → rule-pack judge → result contract JSON (headless, per-run context).
4. Re-express **DEMO** as `projects/DEMO/model.json`; compare verdicts with legacy run (must match).
5. Importer: Postman → model JSON (no generated Java).
6. Secrets → `${ENV:}`/secret ref; scrub `config.properties` in a copy for sharing.
7. Exit: DEMO via model == DEMO via legacy; Maven build green; `docs/phase-1-notes.md`.
