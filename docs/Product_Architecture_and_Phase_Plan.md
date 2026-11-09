# Universal API Quality Platform — Final Architecture & Phase Plan

**Status:** Planning document (no code changes made). **Date:** 2026-10-14
**Inputs analysed:** `API_prod_prompt.txt`, `AI_API_Automation_Engine_Blueprint.docx`, `Universal_API_Quality_Automation_Product_Master_Blueprint_FIXED.docx`, and the existing repository (`API_Automation`, Java 17 / Rest Assured 5.4 / TestNG 7.10 / Allure 2.24 / POI / Jackson / Playwright).

---

## 1. Decision summary (read this first)

| # | Decision | Reason |
|---|---|---|
| D1 | **Keep Java 17 + Rest Assured + Maven as the only execution engine.** | It already works for RNSB, SPCBL, TNSC, CKYC. A rewrite adds risk and no customer value. |
| D2 | **Reject the Python/pytest design** in `AI_API_Automation_Engine_Blueprint.docx` as an engine. Keep only its *ideas*: token-exchange fixture, "positive-only collection in → matrix out", self-healing on schema change. | Two engines = two maintenance burdens. The same ideas fit in Java behind the common model. |
| D3 | **One normalized, versioned API model.** Importers (Postman, OpenAPI, cURL, HAR…) and the manual UI all produce it; the engine only reads it. | Removes per-project Java (the current `pages/rnsb`, `pages/spcbl`… pattern). |
| D4 | **AI is advisory, never the judge.** PASS/FAIL comes from deterministic rules (`api-test-rules.properties`). AI drafts, explains, triages — humans approve. | Matches existing `AiVerifier` ("rule-based decides; AI advisory") and the commercial/safety posture. |
| D5 | **Modular monolith + isolated workers**; DB-backed queue first; PostgreSQL; k6/Gatling only for load. | Smallest thing that can be sold and later split. |
| D6 | **Deliver phase-wise, one phase per working session**, each with its own audit-light, checklist and exit test. | Token budget is limited (see §10). |
| D7 | **Local-first AI** (Ollama already supported) with provider adapter; PII masked before any external call. | Banking customers; data must not leave the machine by default. |

---

## 2. What already exists (the product's seed)

Derived from folder/file inspection. *Sizes and responsibilities are from names and line counts; a line-level audit is Phase 0.*

| Capability | Existing asset | Product role |
|---|---|---|
| Execution core | `engine/ExecutionEngine` (1078 lines), `runners/BaseApiRunner` (520) | **Becomes the worker's execution kernel.** Needs a model-driven entry point. |
| Per-project drivers | `runners/*_Runner`, `pages/{rnsb,spcbl,ckyc,demo,common}`, `PageRegistry`, `suites/*.xml` | **The thing to retire** — replace with data (model + templates). Keep until regression-equivalent. |
| Test data | `ExcelUtils`, `TestDataCatalog`, `API_TestData`, `PayloadResolver`, `payloads/` | Test-data adapter (Excel/JSON) → reuse. |
| Importer | `tools/PostmanImporter` (502) | Seed of the Importer module; must emit the normalized model. |
| Negative / boundary generation | `negative/NegativeGenerator` (430), `ExpectedStatus` | Seed of the deterministic test-generation engine. |
| Judging rules | `rules/api-test-rules.properties`, `docs/API_Test_Rulebook.md`, `Check/Checks`, `TestDecision` | **Crown jewel** — the rule pack (status standards, CWE/OWASP mapping, strict mode). Make it a versioned, per-project-overridable rule pack. |
| Failure intelligence | `report/FailureClassifier`, `ApiFailure`, `utils/FailureAnalyzer`, `ResponseAnalyzer`, `JsonDiffUtil` | Seed of the failure-triage service / agent input. |
| AI | `ai/AiVerifier`, `AiVerdict` (Ollama / OpenAI-compatible, PII-masked, advisory) | Seed of the **LLM provider adapter** + first agent tool. |
| Security | `security/EnvelopeCrypto`, `SessionFilter`, `utils/PiiMasker` | Redaction + request-envelope plug-ins. |
| OTP / human-in-loop | `otp/*` (dialog, service, chime) | "Human gate" pattern — reuse for approvals. |
| Console | `dashboard/LiveDashboard` (475), `RunView`, `TestView`, `resources/dashboard/dashboard.html`, `docs/live-console/` | The existing console → evolve into the product UI; add persistence. |
| Reporting | Allure, `ResponseStore`, `reports/` | Keep; add normalized result contract + HTML/PDF/CSV export. |
| Docs | `docs/` handover guide (docx), handover template (xlsx), Burp guide, RCA of console test count | Onboarding collateral; the **handover template is effectively the manual-config schema** — mine it for the model. |

### Known structural gaps vs. product
1. Everything is **test-source-tree code** (`src/test/java`, empty `src/main`) — no packaged, reusable library.
2. **Project knowledge lives in Java classes** (`pages/rnsb/*Page.java`) instead of data.
3. **No persistence** — state is files and in-memory dashboard; no run history, no tenancy, no users.
4. **No queue/worker separation**; runs are TestNG-process bound.
5. **No versioned model or result contract**; secrets/config in `config.properties`.
6. AI is a *verifier only*; no generation, triage, or healing loop.

---

## 3. Target architecture

```
┌────────────────────────── Web Console (evolve existing dashboard) ──────────────────────────┐
│ Dashboard · Import Center · API Explorer · Test Studio · Pre-Launch · Run Workspace · Reports │
│ Security · Performance · Monitoring · AI Assistant · Admin                                   │
└───────────────────────────────────────────────┬─────────────────────────────────────────────┘
                                                │ REST + SSE (live events)
┌───────────────────────────────────────────────▼─────────────────────────────────────────────┐
│ Platform API (Spring Boot, modular monolith)                                                │
│  tenancy/RBAC · projects/envs · api-model · import · test-studio · run-orchestrator ·       │
│  results · findings · schedules · AI-gateway · audit · plans/quotas                         │
└───┬────────────┬─────────────────┬───────────────────────┬───────────────┬──────────────────┘
    │            │                 │                       │               │
 PostgreSQL   Artifact store   Job queue (DB-backed)   Secret store    Vector store (pgvector)
    │         (reports/evidence)      │               (encrypted/KMS)   (tenant-filtered RAG)
    │                                 │
┌───▼─────────────────────────────────▼───────────────────────────────────────────────────────┐
│ Workers (isolated processes)                                                                │
│  Functional Worker  = existing Java/RestAssured engine behind `ModelRunner`                 │
│  Security Worker    = rule pack checks (authZ, headers, error leakage, input validation)    │
│  Perf Adapter       = k6 or Gatling (separate process, hard caps)                           │
│  Monitor Worker     = scheduled health checks                                               │
│  AI Worker          = agent runtime (see §4)                                                │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

**Module boundaries (Maven multi-module, introduced gradually):**

| Module | Content | Source today |
|---|---|---|
| `core-model` | API model, test-case model, result contract, JSON schema, versioning | new |
| `core-engine` | `ModelRunner`, assertion/extraction/chaining, auth handlers, redaction | extract from `ExecutionEngine`, `ExtractionUtil`, `RequestBuilder`, `PiiMasker`, `SessionFilter` |
| `core-rules` | Rule pack loader + judge | `api-test-rules.properties`, `Checks`, `TestDecision` |
| `core-import` | Importer SPI + adapters | `PostmanImporter` |
| `core-generate` | Deterministic generators | `NegativeGenerator` |
| `core-ai` | Provider SPI, agents, RAG, guardrails | `AiVerifier` |
| `legacy-projects` | RNSB/SPCBL/TNSC/CKYC runners & pages | current tree, **frozen**, used as regression oracle |
| `platform-api`, `worker`, `console` | Phase 3+ | new / evolved dashboard |

Rule: **new code never imports from `legacy-projects`.** Legacy is the regression oracle until each project is re-expressed as data.

### 3.1 Normalized API model (v1 sketch — to be frozen in Phase 0)
```json
{
  "modelVersion": "1.0",
  "api": {"name":"", "version":"", "source":{"type":"postman|openapi|manual|curl|har","ref":"", "hash":""}},
  "environments": [{"name":"QA","baseUrl":"","scopeAllowlist":[""]}],
  "authProfiles": [{"id":"","type":"oauth2|apikey|session|otp|custom","secretRef":"","steps":[]}],
  "endpoints": [{
    "id":"", "method":"POST", "path":"/v1/x", "contentType":"application/json",
    "headers":{}, "query":{}, "pathParams":{},
    "requestSchema":{}, "responseSchemas":{"200":{}},
    "envelope": {"type":"none|aes-gcm|custom","pluginRef":""},
    "extract":[{"var":"token","jsonPath":"$.data.token"}],
    "dependsOn":[], "risk":"low|med|high", "idempotent": true
  }],
  "testCases": [{
    "id":"", "endpointId":"", "category":"positive|missing|type|boundary|enum|auth|security|business|contract",
    "mutation":{}, "expected":{"status":[200,201],"assertions":[]}, "ruleRef":"",
    "lifecycle":"DRAFT|REVIEWED|APPROVED|ACTIVE", "version":1, "origin":"manual|generated|ai", "tags":[]
  }]
}
```
Two project-specific realities this must absorb (seen in the repo): **encrypted envelopes** (`EnvelopeCrypto`) and **OTP human gates** (`otp/*`) → modelled as `envelope` plug-ins and `humanGate` steps, not hard-coded classes.

### 3.2 Result contract (v1)
`orgId, projectId, envId, runId, testCaseId, testCaseVersion, startedAt, endedAt, durationMs, outcome ∈ {PASS, FAIL, BLOCKED, SKIPPED, NOT_EXECUTED}, passWithRemark(bool), failureCategory, severity, cwe/owasp, expected, actual, request/response (redacted), retries[], worker{id,version}, ruleVersion`. Run status is stored separately from console/worker connectivity.

---

## 4. AI agent architecture

### 4.1 Principles
1. **Deterministic first, AI second.** The product works with AI off (existing `ai.enabled=false`).
2. **Propose → human approves → engine executes.** Agents write only `DRAFT` objects.
3. **Tools are narrow.** Agents call typed tools; they never get raw network, filesystem, DB or secrets.
4. **Untrusted input.** API responses, imported files, website text = data, never instructions (prompt-injection boundary).
5. **Tenant-scoped everything**: retrieval, memory, prompts, logs.
6. **Everything audited**: prompt hash, model, tool calls, tokens, who approved.
7. **Cost-capped**: per-run and per-tenant token budgets; cache by content hash.

### 4.2 Agent roster

| Agent | Phase | Input | Tools (allow-list) | Output | Approval |
|---|---|---|---|---|---|
| **Import Analyst** | 2 | Raw spec/collection + parser warnings | `parse_spec`, `diff_model` | Cleaned model draft, unsupported-feature report, missing-info questions | Reviewer |
| **Test Designer** | 2–3 | Normalized endpoint + rule pack + docs (RAG) | `get_endpoint`, `generate_deterministic`, `search_docs` | Extra business-rule / stateful-flow candidates (`origin=ai`) | Tester → Approved |
| **Failure Triage** | 3 | Redacted result, rule verdict, `FailureClassifier` category, history | `get_result`, `get_run_history`, `diff_json` | Root-cause hypothesis, owner (API / data / env / test), confidence, suggested fix | Advisory |
| **Self-Healing** | 4 | Spec diff between versions + failing tests | `diff_model`, `propose_patch` | Patch as **new test version (DRAFT)** with diff | Tester |
| **Security Analyst** | 4 | Findings + evidence + rule pack | `get_finding`, `get_endpoint` | Severity rationale, remediation text, retest plan — *no claim from status code alone* | Security owner |
| **Report Narrator** | 3 | Persisted aggregates only | `get_run_summary`, `get_trend` | Executive summary text | Auto, labelled "AI-generated" |
| **Assistant (RAG chat)** | 4–5 | Question + tenant docs/spec/history | `search_docs`, `get_*` | Cited answers; "unknown" when no source | n/a (read-only) |

Out of scope for v1: autonomous execution, autonomous production load, autonomous browser exploration (see 4.5).

### 4.3 Runtime components
- **LLM Provider SPI** (`LlmClient`): `ollama` (default, local) · `openai-compatible` · later Claude/Bedrock. Exactly the seam `AiVerifier` already has — generalise it.
- **Guardrail layer** (pre/post): PII masking (`PiiMasker`), secret scrubber, size limits, JSON-schema validation of outputs, refusal on policy violations, retry/backoff, deterministic fallback.
- **Prompt registry**: versioned prompt files (`prompts/<agent>/<version>.md`) with eval fixtures; no prompts embedded in Java strings.
- **RAG**: ingest spec/requirements/runbooks → chunk with `{tenant, project, source, section, version, acl}` → pgvector → retrieve with tenant+ACL filter → answer with citations. (Defer until Phase 4; before that, plain structured retrieval from the model is enough.)
- **Eval harness**: golden fixtures per agent (e.g. 20 endpoints → expected candidate coverage; 20 failures → expected category). Prompt change = must pass evals. Prevents silent regressions.

### 4.4 Agent ↔ engine contract
Agents consume/produce the **same JSON model** (§3.1). An AI-generated test is just a `testCase` with `origin=ai, lifecycle=DRAFT`. The engine cannot tell it apart from a manual one once approved — which is the point.

### 4.5 MCP / browser discovery (deliberately late)
Only after Phase 4, behind written scope authorization, allowlisted domains, isolated Playwright profile (Playwright 1.42 already in `pom.xml`), redaction, and a human approval gate. It is a *candidate-endpoint suggester*, not a scanner.

---

## 5. Data & configuration model (logical)

`organizations, users, organization_members, projects, environments, api_definitions(version), endpoints, auth_profiles(secret_ref), rule_packs(version), test_suites, test_cases(version, lifecycle), test_runs, test_results, performance_runs, security_findings(+retests), schedules, alerts, documents, retrieval_chunks, ai_invocations(audit), audit_events, plans, usage_counters`.

Rules: every row carries `organization_id` (+`project_id`); backend-enforced tenant filter (plus PostgreSQL row-level security as defence in depth); definitions immutable once referenced by a run; raw bodies in artifact store, redacted before persistence; migrations via Flyway; retention per plan.

---

## 6. Security & tenancy (non-negotiables)
- Tenant isolation tested with cross-tenant integration tests **before** any external pilot.
- Secrets only as references; no tokens/cookies/OTPs in logs or prompts.
- SSRF controls: target allowlist, no redirects off-list, block link-local/metadata ranges.
- Imported scripts (Postman pre-request) never executed unsandboxed; unsupported → reported, not silently dropped.
- Security/perf runs require explicit authorization + scope + window; hard caps; production off by default.
- Audit on config change, approval, execution, AI invocation, export.

---

## 7. Phase plan (each phase = 1–3 working sessions, token-aware)

Each phase ends with: **green legacy regression**, a short `docs/phase-N-notes.md`, and a git tag. Never start the next phase without explicit approval.

| Phase | Goal | Key deliverables | Exit test | Rough effort |
|---|---|---|---|---|
| **0 Audit & freeze** | Know exactly what exists | File-level reuse map; baseline run of every suite (record pass/fail counts); freeze model v1 & result contract v1; ADRs for D1–D7 | Baseline numbers stored; model schema reviewed | 1 session |
| **1 Reusable core** | A new API runs with **zero new Java** | `core-model`, `ModelRunner` wrapping `ExecutionEngine`; rule pack as data; manual config via JSON/Excel; result contract + JSON/HTML export; re-express **DEMO** project as data | DEMO via model = DEMO via legacy (same verdicts) | 2–3 sessions |
| **2 Import & generation** | Onboard from spec/collection; reviewable tests | Importer SPI; Postman v2.1 + OpenAPI (+cURL); `core-generate` from `NegativeGenerator`; lifecycle states; fixtures; **Import Analyst + Test Designer agents (read-only drafts)**; `LlmClient` SPI | Fixture specs import with warnings; generated suite executes through same engine; re-express **TNSC** as data | 2–3 sessions |
| **3 Platform foundation** | Multi-tenant product skeleton | Spring Boot API, PostgreSQL+Flyway, RBAC, tenancy, secret refs, DB queue + worker, run history, audit, console wired to API; **Failure Triage + Report Narrator** | Cross-tenant tests pass; async run + cancel works; re-express **RNSB/SPCBL/CKYC** (envelope + OTP plug-ins) | 3–4 sessions |
| **4 Quality extensions** | Security, perf, monitoring, healing, RAG | Security worker (rule pack); k6/Gatling PoC→adapter; schedules/alerts; findings lifecycle & retest; **Self-Healing, Security Analyst, RAG Assistant**; eval harness | Safety caps verified; healing produces DRAFT patches only | 3–4 sessions |
| **5 Commercial readiness** | Pilotable | Plans/quotas/feature flags, onboarding, Docker packaging, backup/restore, load + isolation tests, ops monitoring, pilot checklist | Pilot checklist signed off | 2–3 sessions |

**Parallel-able:** console UI work (Phase 3) can overlap Phase 2 importers once the model is frozen.
**MVP cut line (earliest sellable demo):** end of Phase 2 — tester imports a collection, reviews/approves generated tests, runs them, sees an honest report, no Java edits.

---

## 8. Immediate next step (Phase 0 — when you say go)
1. Run each suite once and record baseline counts (RNSB, SPCBL, TNSC, CKYC, DEMO).
2. Read the 6 core classes fully (`ExecutionEngine`, `BaseApiRunner`, `PostmanImporter`, `NegativeGenerator`, `FailureClassifier`, `LiveDashboard`) and produce the reuse/gap table.
3. Freeze model v1 and result contract v1 (JSON Schema files in `docs/schemas/`).
4. Decide the open questions below.

## 9. Open decisions (need your answer before Phase 1/3)
| # | Question | My recommendation |
|---|---|---|
| Q1 | Console: keep current dashboard tech or React+TS? | Keep for Phase 1–2; move to React+TS at Phase 3 when persistence arrives. |
| Q2 | Deployment: SaaS vs on-prem for banks? | Design Docker-first for on-prem; SaaS is a later packaging of the same image. |
| Q3 | AI provider default | Ollama local; optional external only with tenant opt-in + masking. |
| Q4 | Perf engine | k6 (scriptable, simple CLI, easy caps); Gatling only if Java-DSL reuse matters. |
| Q5 | Repo layout | Convert to multi-module Maven in Phase 1 inside this repo; no new repo yet. |
| Q6 | Commercial/licensing of Rest Assured, Playwright, models | Review before Phase 5; all current deps are permissive but verify. |

## 10. Working agreement for limited tokens
- One phase per session; open with "Phase N, checklist only" and approve before coding.
- Read files by range, not whole; keep `docs/phase-N-notes.md` as the handoff so context never needs re-deriving.
- Use the memory entry (project memory) + this document as the only context to re-load.
- Prefer subagents only for independent audits; avoid broad dumps.

## 11. Risks
| Risk | Mitigation |
|---|---|
| Legacy per-project Java hides undocumented behaviour | Legacy-as-oracle regression; migrate one project per phase |
| "Zero-Java onboarding" fails for bespoke flows (encryption, OTP) | Plug-in SPI (`envelope`, `humanGate`, custom `authStep`) instead of project classes |
| AI cost/latency/non-determinism | Local model default, caching, evals, advisory-only |
| Scope creep (13 import formats) | Only Postman, OpenAPI, manual, cURL in MVP; others per demand with fixtures |
| Tenant leak | RLS + cross-tenant test suite gate |
| Single-developer capacity | Phase gates; defer security/perf/billing past MVP |
