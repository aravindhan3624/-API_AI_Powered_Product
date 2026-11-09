# Failure playbook - how to read a failed or blocked test

## UNEXPECTED_STATUS (positive test)
Meaning: a valid request did not return the expected success code.
Check in order: 1) 401/403 - session, token or CSRF missing or expired, wrong role; 2) 404 - wrong path, id does not exist, endpoint not deployed; 3) 400/422 - request body no longer matches the contract (new mandatory field, changed format); 4) 409 - data already exists or state conflict, change the test data; 5) 5xx - server defect, collect the response and ask the API team.
Owner: test data or environment first, then API team.

## INVALID_REQUEST_ACCEPTED (negative test)
Meaning: bad input was accepted with a 2xx status. For security categories (no session, tampered token, other user) this is critical. For plain validation it is medium.
Check: is the validation done at all (compare with the specification), or is the endpoint public by design? Confirm with the API owner before logging a bug.
Owner: API developer. Fix: add validation or authorisation.

## SERVER_ERROR_ON_BAD_INPUT
Meaning: the server crashed (5xx) on invalid input. CWE-209 information exposure and a robustness defect even when the body is clean.
Evidence needed: the exact request, the status and the body. Fix: validate input early and return 4xx.

## ASSERTION_FAILED
Meaning: the status was right but a body check failed (missing field, wrong value, too slow).
Check: did the contract change, is the test data different from the expected value, is the latency threshold realistic?

## BLOCKED - variable not available
Meaning: an earlier step that should have produced a value (token, id, extracted field) failed, was skipped or returned a different shape. The test was not sent.
Fix: open the earlier step, fix it first. The blocked tests are not failures of the API.

## BLOCKED - credentials not configured / AUTH_NOT_CONFIGURED
Meaning: the endpoint needs credentials and none were configured, or the value is empty.
Fix: add an auth profile and set the environment variable or -Dvar.NAME value. Never put secrets into the model file.

## BLOCKED - waiting for OTP
Meaning: the step needs a human-entered OTP. Run attended (popup) or pass -Dvar.OTP.

## BLOCKED - transport error
Meaning: connection refused, timeout, TLS or DNS problem. Check VPN, host name, proxy and the target allow-list.

## NOT_EXECUTED
Meaning: the test was not eligible to run: lifecycle is DRAFT/REVIEWED, Run=N in the source data, or its body type is not supported yet.

## Flaky results
Same test passes and fails on repeated runs: shared test data, rate limits, session takeover (another login invalidated the session), server instability. Re-run twice; only report as a defect when it repeats.

## Pass rate
Pass rate = passed / (passed + failed). Blocked, skipped and not-executed tests are excluded from the denominator and shown separately, so a high pass rate with many blocked tests is not a healthy run.

## Account locked / LOCKOUT_GUARD
Meaning: the login answered that the account is locked after multiple failed attempts, or the product skipped login negatives on purpose to prevent that. Every later test that needs the session is blocked or fails.
Fix: ask the administrator to unlock the test account, use a separate account for negative login tests, keep the per-run cap low, and avoid running several suites against the same account in parallel.
