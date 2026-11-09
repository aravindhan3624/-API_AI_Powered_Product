# OWASP API Security Top 10 (2023) - how this product tests each risk

## API1:2023 Broken Object Level Authorization (BOLA / IDOR)
Risk: a logged-in user reads or changes another user's object by changing an id in the path, query or body.
Test: call the endpoint with an id that belongs to a different user/tenant (category OTHER_USER). Expected 403 (or 404/401), never 200 with data.
Evidence: response contains another user's data. Severity: critical when personal or financial data is returned.
Fix: authorise every object access on the server using the session identity, never trust ids from the client.

## API2:2023 Broken Authentication
Risk: weak or missing authentication, forged or reused tokens, no lockout.
Tests: NO_SESSION (no token/cookie, expect 401), TAMPERED_TOKEN (last characters changed, expect 401), WRONG_PASSWORD (expect 401 or 400 with a generic message), expired token.
Findings to report: endpoint works without a session, forged token accepted, different messages for unknown user vs wrong password (user enumeration), no rate limit on login.
Fix: validate signature and expiry on every request, generic login errors, lockout and rate limiting.

## API3:2023 Broken Object Property Level Authorization
Risk: mass assignment (client sets fields like role, isAdmin, balance) or excessive data exposure in responses.
Tests: add unexpected fields to the body (role, admin, id, status) and check they are ignored or rejected; compare response fields with the documented schema.
Fix: allow-list writable properties, return only needed fields.

## API4:2023 Unrestricted Resource Consumption
Risk: no limits on payload size, page size, rate or expensive operations.
Tests: LARGE_PAYLOAD (about 1 MB body, expect 413 or 400), very large page size or limit, repeated calls (use performance tests with hard caps, never against production).
Fix: size limits, pagination caps, rate limiting, timeouts.

## API5:2023 Broken Function Level Authorization
Risk: a normal user can call admin or other-role functions.
Tests: call privileged endpoints with a low-privilege session, switch HTTP method (GET to DELETE), try admin paths. Expect 403.
Fix: deny by default, role checks per function.

## API6:2023 Unrestricted Access to Sensitive Business Flows
Risk: automated abuse of flows such as OTP sending, account creation, coupon use.
Tests: repeat the same business call quickly within safe limits; verify OTP resend limits and expiry.
Fix: per-user and per-device limits, captcha or step-up verification.

## API7:2023 Server Side Request Forgery (SSRF)
Risk: the API fetches a URL supplied by the client and reaches internal systems.
Tests: only with written authorisation - supply a harmless external URL you control, never internal metadata addresses against third parties.
Fix: allow-list outbound targets, block private and link-local ranges.

## API8:2023 Security Misconfiguration
Risk: verbose errors, missing security headers, permissive CORS, unexpected methods, debug endpoints.
Tests: WRONG_METHOD (expect 405), check headers (Strict-Transport-Security, X-Content-Type-Options, Cache-Control on sensitive data), error bodies without stack traces or SQL text (CWE-209), tampered encryption envelope rejected (CWE-345).
Fix: hardened defaults, generic error messages, only needed methods and origins.

## API9:2023 Improper Inventory Management
Risk: old API versions, undocumented endpoints, test hosts still reachable.
Tests: compare captured traffic with the imported specification, list endpoints present in traffic but missing from the spec.
Fix: maintain an inventory, retire old versions.

## API10:2023 Unsafe Consumption of APIs
Risk: trusting data from third-party APIs without validation.
Tests: feed malformed upstream-style responses to mocks where possible; check input validation on callback endpoints.
Fix: validate and sanitise all third-party data, enforce timeouts and TLS.

## Injection markers (CWE-89, CWE-79, CWE-20)
Safe markers only: a single quote, a harmless tautology string or a script tag text inside string fields. A 500 response, SQL or stack-trace text in the response, or the script text echoed back unescaped is a finding. A plain 200 is not proof of a vulnerability - confirm the impact before labelling it.
