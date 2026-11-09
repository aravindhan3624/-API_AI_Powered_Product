# API Test Rulebook (all projects)

**Owner:** Aravindhan D · **Applies to:** every project in this framework (RNSB, SPCBL, TNSC, CKYC and any new one). Nothing needs to be set up per project.
**Source of truth for the code:** `src/test/resources/rules/api-test-rules.properties`

## 1. How every response is judged

| What came back | Verdict | Report |
|---|---|---|
| **Valid request** → expected code (200 / 201 / 202, or the `ExpectedStatus` column) | PASS ✓ | Closed |
| **Valid request** → anything else | FAIL | Category + plain-English reason + who must act |
| **Invalid request** → the **standard code** below | PASS ✓ | Closed |
| **Invalid request** → rejected (4xx) but **another code** (e.g. 422 instead of 401) | PASS ⚠ with remark *"Wrong status code: got 422, the standard is 401 because …"* | Shown in Summary table 6. Set `negative.strict.status=true` to make it FAIL |
| **Invalid request** → **5xx** | FAIL, High | Server crash on bad input (CWE-209) |
| **Invalid request** → response contains exception / SQL / stack text | FAIL, High | Information leak (CWE-209) |
| **Invalid request** → **accepted** (2xx with data) | FAIL. Critical for security checks, Medium for data validation | Open – Bug |
| Valid request again **after** the negatives fails | FAIL, Critical | The negatives changed session / data (API6) |

## 2. Expected HTTP code per check (HTTP standard RFC 9110)

| Check | Expected | Why | VAPT reference |
|---|---|---|---|
| No login / session / token | **401** | not logged in | OWASP API2:2023 Broken Authentication |
| Tampered bearer token (real token, last characters changed, session cookies altered) | **401** | forged / modified token | API2:2023 · CWE-347 |
| Wrong CSRF token (POST / PUT / PATCH / DELETE only; GET is not CSRF-checked by design) | **403** (or 401) | cross-site request forgery | OWASP A01 · CWE-352 |
| Malformed XML (XML / SOAP APIs) | **400** (or 422) | not well-formed | CWE-20 |
| XML external entity, XXE (XML / SOAP APIs) | **400** (or 422), file content never returned | entity reading a server file | OWASP A05 · CWE-611 |
| Wrong password | **401** | wrong credentials | API2:2023 Broken Authentication |
| Another user's id | **403** (or 401) | must not act for another user | API1:2023 BOLA / IDOR |
| Tampered encryption | **400** (or 401) | request cannot be trusted | API8:2023 · CWE-345 |
| Wrong HTTP method | **405** | only its own method is allowed | API8:2023 Security Misconfiguration |
| Very large payload (1 MB) | **413** (or 400) | far larger than any valid body | API4:2023 Unrestricted Resource Consumption |
| Not-existing record | **404** (or 400 / 422) | record does not exist | API3:2023 · CWE-20 |
| Wrong type, missing, empty, spaces only, invalid code, too long | **400** (or 422) | invalid request | CWE-20 Improper Input Validation |
| SQL injection characters | **400** (or 422) | must be rejected / safely handled | OWASP A03 Injection · CWE-89 |
| Script / HTML tags | **400** (or 422), never echoed back | XSS | A03 Injection · CWE-79 |
| Special / Unicode characters | **400** (or 422) | outside the allowed set | CWE-20 |
| Impossible date, wrong format, from > to, future date | **400** (or 422) | invalid date | CWE-20 |
| DOB today / future / 31 Feb / 1800 | **400** (or 422) | KYC rule | CWE-20 · KYC data quality |
| Valid request after the negatives | **200** | invalid input must not change state | API6:2023 Sensitive Business Flows |

**About 402:** *402 Payment Required* is reserved for payment and billing APIs. It is not a correct answer for any of the checks above.

## 3. Write APIs (save / update / delete) and round trips
- **Write APIs get only the security checks:** no session, tampered token, wrong CSRF, another user's id and wrong method. Invalid-data checks would create junk records, and re-sending the valid request would create duplicates, so neither runs on a write API.
- **Round trip** (write → read back → confirm): create a record with a unique `AUTOQA_<time>` name, read it back and compare each field, update it, read back again, delete it, and read back to confirm it's gone. The run always cleans up after itself. For CKYC this covers the Email recipient group and group member (module 18). Read-back checks use the `Validate` column, e.g. `rows.find{it.GROUP_CODE=='${crudCode}'}.GROUP_NAME=${crudName}`.

## 4. Traceability: every result shows where it came from
| Column | Example |
|---|---|
| Format (request → response) | `JSON (encrypted) → JSON`, `XML → XML`, `no body → PDF` |
| Data source | `CKYC_testdata.xlsx › Sheet1 › row 48` · `Payload: payloads/CKYC/DVS04….json` · `Generated: negative of TC047 - rule TAMPERED_TOKEN` · `cmId = 3 ← TC046 … (live)` |
| Report header | Tested by, runner + suite, test-data file, environment, base URL |

Relative paths only; passwords, tokens and PII are never shown.

**XML / SOAP APIs:** put the body in a `.xml` payload file (or raw XML in the Payload cell). It's sent as `application/xml`, the XML response is converted so Extract / Validate paths work (`Envelope.Body.Resp.cmId[0]`, attributes as `@name`), and the XML negatives above are generated automatically.

## 5. Changing a rule for one bank
A bank with a different standard overrides a rule in `config.properties`, without any code change:
```
SPCBL.negative.expect.NO_SESSION=401,403
negative.strict.status=true      # treat "wrong status code" as FAIL instead of a warning
```

## 4. Where you see it
- **Live console:** the HTTP column shows *actual · exp …* with ✓ / ⚠. Click a row for Base URL, API path, Full URL, expected vs actual HTTP and the VAPT reference.
- **Excel:**
  - *Expected HTTP* / *Actual HTTP* / *VAPT / OWASP reference* columns on every result sheet;
  - Summary table **6 – HTTP status code check**;
  - **Bug Report** with steps, expected, actual, VAPT reference, *Tested by*.
