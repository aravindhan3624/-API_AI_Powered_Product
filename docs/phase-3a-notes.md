# Phase 3a - Test Studio + built-in AI assistant (2026-10-22)

## Incident to know about (CKYC DEVHOST)
While testing the generator against the real CKYC login I ran ~26 credential-style negatives (missing/null/empty/oversized userName and password...). The server answered **"Your account is locked due to multiple failed login attempts"**: the shared automation account `apiauto` on DEVHOST is **locked and needs an administrator to unlock it** until then CKYC legacy runs will fail at login.
Fix in the product (verified on DEMO): login endpoints get only a few negatives (2 field-removals + wrong method, oversized body, mass assignment), and the runner caps negatives per login endpoint per run (default 4, `-Dlockout.max=N`); the rest are BLOCKED with the reason `LOCKOUT_GUARD`. Knowledge base, failure explainer and the assistant explain lockouts.

## What was built
| Area | Files |
|---|---|
| Test Studio server + UI (local, 127.0.0.1 only, offline) | `studio/StudioServer.java`, `StudioMain.java`, `RunService.java`, `resources/studio/studio.html`, `studio.bat` |
| Lifecycle DRAFT -> REVIEWED -> APPROVED -> ACTIVE (steps enforced, edit of an approved test = new version + back to DRAFT, audit log, 30 model backups) | `StudioServer`, `core/store/ProjectStore.java` |
| Import (Postman / OpenAPI / Swagger JSON) by upload or path; duplicate/bad files refused | `StudioServer` |
| Background runs with live progress, cancel, HTML + Excel report links | `RunService` |
| Assistant engine (no model needed): intent routing, run summary, failure explanation, blocked analysis, test generation, BRD/flow analysis, knowledge answers with sources, secret-fishing refusal | `ai/Assistant.java`, `FailureExplainer.java`, `FlowAnalyzer.java`, `TestGenerator.java` |
| Local retrieval over curated knowledge (OWASP API Top 10, failure playbook, test-design techniques, banking/KYC notes, product help) and project documents (.txt .md .docx .pdf) | `ai/KnowledgeBase.java`, `Bm25Index.java`, `resources/ai/knowledge/*.md` |
| Project memory: dismissed suggestions never repeat, accepted counts, expected statuses taught by testers become defaults | `ai/AiMemory.java` |
| Optional local model (Ollama): only rephrases, cannot add facts (numbers checked), auto-disabled when absent | `ai/OllamaClient.java` |
| Generator categories: missing/null/empty/wrong type/boundary/long text, SQL + XSS markers, no session, tampered token, wrong CSRF, tampered encryption, wrong method, 1 MB body, mass assignment, unknown id | `TestGenerator` + runner support (`${PAD:n}`, query mutations, leak/echo detection) |
| Regression eval of the assistant (14 golden questions, offline) | `AssistantEvalTest`, `suites/testng-AI-EVAL.xml` - **14/14** |

## Verified by running
- Studio API on DEMO: generate 24 drafts -> add -> bulk steps enforced (DRAFT->APPROVED refused) -> edit approved test = v2 + DRAFT -> run 11 tests in background -> report -> history.
- UI opened in the built-in browser (project list, lifecycle chips, table, edit drawer, assistant panel; no console errors).
- CKYC (encrypted, cookie session) through Studio: 31-34 generated negatives ran with correct judging; NO_SESSION 422 and tampered token 401 match the legacy results for TC006.
- BRD analysis on .txt + .pdf; .docx read check; upload import of Petstore; CSRF-style header guard (403 without `X-Studio`).

## Honest limits
- The assistant is a **rule-based expert engine + retrieval**, not a language model. It does not "learn" weights. It improves through knowledge files, project memory and the eval suite.
- No local LLM is installed. i5-8400 / 16 GB / GT 710 means CPU-only: a 3B model gives roughly 5-10 tokens/s, 7B is slow. Install is optional and needs your OK (see below).
- BRD analysis finds numbered steps, explicit `METHOD /path` mentions and rule sentences; it does not understand free prose beyond that. Business rules are listed for a human, never auto-created.
- Legacy Excel projects and YAML specs are still imported from the command line.
- After the lockout I did not re-run CKYC; the post-fix generator was verified on DEMO only.

## Start it
`studio.bat` (or: `mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt`, then `java -cp "target/classes;<cp>" com.api.product.studio.StudioMain`) -> http://localhost:8099
Secrets: set environment variables first (e.g. `CKYC_PASSWORD`); `-Dotp.dialog.off=true` for unattended runs.
Assistant eval: `mvn -q test -Dsuite=suites/testng-AI-EVAL.xml`.

## Optional: local model (Ollama)
1. Install Ollama for Windows (free, no account). 2. `ollama pull qwen2.5:3b` (about 2 GB). 3. Restart Studio: the badge shows "AI: rules + local model". Disable with `-Dai.llm=off`.

## Next
Negative-generator parity run against the legacy 1,269 negatives on a safe project, Playwright flow capture (log in yourself, record calls, diff with the collection), performance/monitoring, user manual PDF.
