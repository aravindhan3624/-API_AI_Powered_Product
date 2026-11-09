# Test design techniques for APIs

## Positive tests
One valid request per operation using schema examples, required fields and valid test data. Assert the status from the contract, the presence and type of key response fields and a sensible latency limit. Chain dependent calls (login, create, read, update, delete) and clean up created data.

## Missing and empty fields
Remove each required field one at a time. Also send null, empty string and a string of spaces. Expected result comes from the contract or business rule: usually a 4xx validation error with a clear message and no 5xx. Do not assume every rejection is 400; 422 is also common.

## Wrong data types
Send a string where a number is expected, a number for a boolean, an array or object for a scalar. Expect rejection, not silent coercion unless the contract says coercion is allowed.

## Boundary values
For each declared limit test below, at and above: minLength/maxLength, minimum/maximum, array sizes, date ranges, decimal precision. Only test limits that exist in the contract or business rules.

## Equivalence classes
Group inputs that the system should treat the same (valid country codes, invalid codes, empty). One representative per class is enough; add the edges of each class.

## Enumerations
Send each valid value once and one invalid value. Check case sensitivity and whitespace.

## Format checks
Email, phone, PAN, Aadhaar, IFSC, pincode, dates: valid format, wrong length, wrong characters, impossible dates (30 February), wrong date format.

## Special characters and injection markers
Quotes, angle brackets, unicode, emoji, very long strings, a safe SQL quote and a script tag text. Look for 5xx, stack traces, echoed unescaped input.

## Authentication and authorisation
No credentials, tampered token, expired token, wrong password, another user's id, low-privilege user on admin function, wrong CSRF token for state-changing calls.

## HTTP method and content type
Unsupported method (expect 405), wrong content type (expect 415), unexpected accept header, oversized body (expect 413).

## Idempotency and state
Repeat the same create (expect conflict or idempotent result), update after delete, delete twice, operations in the wrong order (checker before maker). Do not retry non-idempotent calls automatically.

## Response contract
Check required fields, types, enum values, no extra sensitive fields, consistent error format, correct content type and security headers.

## Pairwise (combinatorial) selection
When many optional parameters exist, cover every pair of values at least once instead of every combination.

## Prioritisation
Run first: login and smoke (positive, critical business flows). Then negatives on money, identity and personal-data endpoints. Then the long tail. Mark risk high for create/update/delete and anything touching personal data.
