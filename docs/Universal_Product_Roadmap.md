# Universal API Quality Studio - release roadmap (decided 2026-10-22)

## Decisions (from the owner)
- First customer: banks / large enterprises (private network, offline, audit and security first).
- v1.0 delivery: local app with installer (one tester PC or one office PC). Shared Team Server later.
- Protocols after REST (all wanted): SOAP/XML (WSDL), GraphQL, performance/load, gRPC/WebSocket.
- First real end-to-end proof: CKYC dev, only after the owner confirms `apiauto` is unlocked.

## Architecture rule
Keep the Java engine. One core (projects, tests, lifecycle, approvals, secrets, reports, VAPT) + one adapter per protocol.
No Spring/React/PostgreSQL rewrite until the Team Server edition.

## v1.0 "Universal REST" (one phase per session, verified by running)
1. Contract validation (response vs OpenAPI schema) + spec drift detection.
2. Auto dependency graph and chaining (Login -> Create -> Upload), flow editor, setup/cleanup steps.
3. CLI runner + scheduler + CI/CD (Jenkins, GitHub Actions, Azure DevOps) with exit codes and JUnit XML.
4. Requirement -> assertion review (BRD rules become reviewable assertions with ambiguity flags) + synthetic test data.
5. Real proof on CKYC dev (positive, negatives capped, VAPT) - needs apiauto unlocked.
6. Installer, offline licence key, user manual PDF, security hardening, audit export.

## v1.5 "Enterprise protocols"
SOAP/XML (WSDL import, XPath assertions), GraphQL (introspection), mock server, Jira/Azure DevOps export, basic load testing (virtual users, thresholds).

## v2.0 "Team"
Shared server (users, roles, database, audit), gRPC and WebSocket, optional React front end.

## Always
No online token at runtime; local LLM optional (Ollama install only when the owner says so); credentials never in project files; cap login negatives; report honestly.

## Progress log
- 2026-10-22 DONE + verified: v1.0 phases 1-4 (contract validation + spec drift; auto dependency chaining + data-flow page + run order setup/test/cleanup; CLI + scheduler + CI templates (exit codes 0-4, JUnit XML); business-rule -> reviewable tests with ambiguity flags + synthetic data), load testing (caps, circuit breaker, HTML report, CLI), SOAP/XML (WSDL import, XPath, fault-aware judging), GraphQL (introspection import, errors-aware judging). Offline suite: `mvn test -Dsuite=suites/testng-CONTRACT.xml` (12 tests).
- Real-system proof (phase 5) waits for the owner's confirmation that CKYC `apiauto` is unlocked.

- 2026-10-22 later DONE + verified: mock server, defect export (Jira/Azure CSV), audit hash chain + export, offline licence (Ed25519; vendor tool; dev key pair in vendor/ - generate a production pair before the first customer release), security headers/CSP, user manual (in-app HTML + PDF), customer package with bundled Java runtime (`tools/make-dist.ps1` -> dist/APIQualityStudio-1.0.0.zip), CLI launcher, WebSocket adapter, gRPC adapter (reflection, unary), team mode (sign-in, roles admin/tester/viewer, per-user audit, lockout, HTTPS option).
- Offline suite now 20 tests (`mvn test -Dsuite=suites/testng-CONTRACT.xml`), assistant eval 33/33 (`-Dsuite=suites/testng-AI-EVAL.xml`).
- NOT done / honest limits: streaming gRPC, WebSocket negatives beyond mutated messages, true multi-tenant isolation, SSO/LDAP, performance of very large projects (3,000+ tests render slowly in the Tests table), real customer-system proof (CKYC after apiauto unlock), production licence key pair, code signing of the Windows package, benchmark against Keploy/Postman/Testsigma.
- 2026-10-23: optional AI engine (off by default; local or remote Ollama; gentle mode; verified only with an in-process stub, never with a real model), Test ideas (built-in + model, validated), plain-English explanations, auto-heal repairs, Karate / Rest Assured export (generated Rest Assured compiles), legacy-style live console + saved report, OTP auto-skip, remembered credentials (round trip verified), Clear old records. CONTRACT suite 25 tests, AI-EVAL 45/45.
