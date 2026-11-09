# VAPT rules, re-score tool and external scanners

Updated 2026-11-02 after the senior VAPT review of the CKYC run `reports/CKYC/2026-11-02_16-07-58` (205 FAIL rows, only 4 real findings).
Code: `src/test/java/com/api/automation/negative/VaptRules.java` (verdict), `ApiKind.java` (API type), `NegativeGenerator.java` (tests),
`report/VaptModel.java` + `PdfReports.java` (report). Rulebook text: `src/test/resources/rules/api-test-rules.properties`.

## 1. When is a negative test a VAPT FAIL?
Only with proof of security impact:

| Proof | Severity |
|---|---|
| Data returned with no session / a tampered token | Critical |
| Login works with wrong credentials | Critical |
| SQL injection: DB error on the probe, boolean differential (`' OR '1'='1` returns more rows than `' AND '1'='2` and than a random value), or a time probe delays >= 5 s twice | High |
| Reflected XSS: payload unencoded in a `text/html` response | High |
| CSRF accepted on a state-changing API; a state-changing API accepts no / tampered auth | High |
| Two-user IDOR: user B receives user A's records | High |
| Data / session changed by accepted negatives (state check against a snapshot) | High |
| Internal detail in ANY response, also 4xx and data rows (exception class, SQLException, `bad SQL grammar`, ORA-, JDBC, Java package names, stack frames, SQL text). Evidence = JSON field + matched text | Medium |
| Corrupted `clientsecret` processed; plain JSON accepted on an encrypted API; oversized body (1 MB / 10 MB) accepted; unneeded HTTP method accepted | Low |
| Short invalid values accepted on a field where other invalid values reach the database (grouped as one finding) | Low |

Not VAPT:
- **PASS**: a rejection; on search / list APIs an empty list, the same data as the valid request, or the list without an optional filter; a get-by-id that returns nothing for a bad id.
- **FUNCTIONAL (QA)**: invalid / future / reversed dates, DOB rules, out-of-range flag / viewMode on report and list filters, 5xx without a leak (`vapt.server.error.as.vapt=true` makes 5xx a VAPT Low). They are listed separately and carry no VAPT severity.
- **NOT APPLICABLE**: CSRF on read-only APIs (also POST reads like `/auth/me`), tampered encryption without an encrypted body (GET / empty body), `userId` changes on system-wide settings (2FA, SMTP, SMS config), a `userId` swap inside one admin login.
- **INCONCLUSIVE**: the get-by-id positive returned no data (fix its test data), or the state check had no snapshot.

API type (`ApiKind`) comes from the endpoint name (get-by-id / search-list / create / update / delete). Override: `api.kind.overrides=getPendingDvsView:SEARCH_LIST,getCustomer:GET_BY_ID`.

## 2. Settings (config.properties; all optional, the defaults are shown)
```
negative.large.payload.mb=1,10        # one large-body test per size
negative.other.user.single=false      # old userId swap inside one login (not IDOR proof)
vapt.sqli.proofs=true                 # boolean + time probes after an accepted injection test
vapt.sqli.time=true
vapt.sqli.time.seconds=5
#vapt.sqli.time.probes=probe1||probe2 # replaces the MySQL / SQL Server / PostgreSQL / Oracle sleep probes
vapt.server.error.as.vapt=false
vapt.pdf.closed.evidence=true         # VAPT PDF section 3: request / response of every closed test
vapt.pdf.closed.max.chars=2500
#http.proxy=127.0.0.1:8080            # send all traffic through Burp Suite / OWASP ZAP
# two-user IDOR (VAPT-005), two LOW-privileged users of different FI / branch, never the admin user:
idor.enabled=false
#CKYC.idor.userA.loginId=...          # passwords: CKYC.idor.userA.password / userB.password,
#CKYC.idor.userB.loginId=...          # or environment variables IDOR_USER_A_PASSWORD / IDOR_USER_B_PASSWORD
#idor.apis=customer,dvs,image,bulk,request,kyc
```

## 3. Re-score a saved run (no requests sent)
IntelliJ: open `tools/VaptRescore.java`, right-click `main` -> Run, program argument `reports/CKYC/2026-11-02_16-07-58`
(no argument = newest CKYC run). Add VM option `-Dpdf=false` to skip the PDFs. Output in `<run>/rescore_<date_time>/`:
Excel, VAPT and Failure PDFs, `VAPT_Rescore_Summary.txt`, `VAPT_Rescore_Changes.csv` (every negative: old -> new verdict).
Limits: the SQL injection probes and the state-check snapshot need a live run (STATE_CHECK rows are INCONCLUSIVE).

Result for 2026-11-02_16-07-58 (checked 2026-11-02): negatives FAIL 191 -> VAPT FAIL 73; 4 findings
F-01 internal details leaked (Medium), F-02 no input validation on mcModule / mcStatus (Low), F-03 encrypted request integrity (Low),
F-04 no body-size limit (Low); 14 functional, 43 not applicable, 59 inconclusive (55 state checks + 4 of getUserById).

## 4. Burp Suite, OWASP ZAP and other tools
The framework already does what a scanner cannot: it encrypts every request with the app's envelope and judges the
decrypted response. Scanners alone only see Base64 ciphertext, so combine them:

| Tool | Use for this project |
|---|---|
| Burp Suite Pro + extension **PyCript** (or a custom Montoya extension) | Decrypt / re-encrypt the CKYC envelope so Scanner, Repeater and Intruder can change the plain JSON. Set `http.proxy=127.0.0.1:8080` to see every automation request in Burp's history. |
| Burp extensions **Autorize** / **AuthMatrix** | Authorization (IDOR / privilege) testing with two or more users, the manual equivalent of `idor.enabled`. |
| **OWASP ZAP** (free) | Automated API scan from the OpenAPI / Postman collection; scriptable (a ZAP client is already in `VAPT_Codes`). Needs an encryption script for encrypted endpoints. |
| **sqlmap** | Confirm a suspected injection (use `--tamper` / `--eval` for the envelope, or point it at a local decrypting proxy). Only on DevHost with permission. |
| **Nuclei** | Template checks for misconfiguration, exposed panels, known CVEs of the server stack. |
| **testssl.sh** / **sslyze** | TLS versions, ciphers, certificate, HSTS. |
| **Schemathesis** / **RESTler** | Property-based / stateful API fuzzing from an OpenAPI spec. |
| **jwt_tool** | If JWTs are used: algorithm confusion, weak secrets, expiry. |

Recommended flow: automation run (finds and proves) -> replay the findings in Burp (`docs/Burp_Replay_Guide.md`) ->
ZAP / Burp active scan through a decrypting extension for wider coverage -> sqlmap only to confirm. Run active scanners
only against DevHost / UAT with written permission, never production.
