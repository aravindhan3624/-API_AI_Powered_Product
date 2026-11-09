# Banking, KYC and CKYC notes for API testing

## Typical flows
Customer onboarding: send OTP to mobile or email, verify OTP, capture identity (PAN, Aadhaar, passport, driving licence, voter id), run AML and UCIC checks, create CIF, open account, maker-checker approval. Each step often depends on values returned by the previous one (reference ids, session ids, tokens).

## Maker-checker
A maker creates or edits a record; a different checker approves or rejects it. Test: maker cannot approve own record, checker sees only pending items, status moves Pending to Approved or Rejected, audit trail is written, a rejected record can be re-submitted.

## OTP handling
OTP is always entered by a person during automation; never read OTP values from API responses and never store them. Test rules: expiry, wrong OTP count limit, resend limit, OTP reuse after success, OTP for another mobile number. Wrong OTP must give a clear error and must not create the record.

## Identity documents
PAN: 5 letters, 4 digits, 1 letter. Aadhaar: 12 digits, first digit 2-9, verified with Verhoeff checksum. IFSC: 4 letters, 0, 6 alphanumeric. Mobile: 10 digits starting 6-9. Pincode: 6 digits. Test valid, wrong length, wrong characters and checksum failures. Use synthetic or approved test identities only; never real customer data in test files.

## Encrypted request and response envelopes
Some bank APIs wrap the JSON in a hybrid envelope: a random AES-256 key encrypts the body (AES-GCM), the key is wrapped with the server RSA public key and sent in a header together with the client public key; the response is encrypted for the client. Negative test: corrupt the wrapped key (expect 400 or 401, never a 5xx or a decrypted answer). Responses must never reveal plain data when decryption fails.

## Session and CSRF
Browser-like sessions use cookies plus an anti-CSRF token header on state-changing calls. Tests: no session (401), tampered session cookie (401), wrong CSRF token on POST/PUT/DELETE (403), GET is normally not CSRF-checked. Concurrent login may invalidate the earlier session (session takeover); keep one login per run.

## Data protection
Mask personal data (mobile, PAN, Aadhaar, email, account numbers) in reports and logs. Responses should not return more personal data than needed. Passwords, tokens and OTP never appear in evidence.

## Environments
Use UAT or dev hosts with approval. Production runs need explicit authorisation and conservative limits. Keep credentials in environment variables or the secret store, never in model files or prompts.

## Regulatory touch points
CKYC uploads and searches have batch limits and mandatory fields; validate error messages for rejected records, duplicate detection, status codes for partial success, and that audit and report downloads work.

## Account lockout risk on login endpoints
Every rejected login (wrong password, missing or invalid user name) can count towards an account lockout policy. A shared automation account that gets locked blocks every later run and needs an administrator to unlock it. The product therefore generates only a few negatives for login endpoints, caps negative attempts per run (default 4, -Dlockout.max=N), and never repeats credential guessing. Use a dedicated test account for negative login tests and agree the lockout policy with the API owner first.
