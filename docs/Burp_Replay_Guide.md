# Confirming an accepted negative in Burp Suite

**Prepared by:** Aravindhan D
**Example used:** `TC246_11` — "Very large payload" on `POST /email/previewEmailTemplate`
**Finding:** the server returned **HTTP 200** (accepted) when it should have returned **413 / 400** (rejected). This proves the API has **no request-size limit** — VAPT: OWASP **API4:2023 Unrestricted Resource Consumption**.

This endpoint is a good first example because the Email module sends **plain JSON** (most other CKYC calls are encrypted and cannot be hand-edited in Burp — see the note at the end).

---

## What the automation found (from the report)

| | |
|---|---|
| TC | `TC246_11` (negative of `TC246`) |
| API | 14 Email Notification > Preview Email Template |
| Method / URL | `POST https://iflowckyc.isolveapps.in/ckyc-api-gateway/ckycBackend/email/previewEmailTemplate` |
| Negative applied | add an extra field with ~1 MB of text |
| Expected | 413 Content Too Large (or 400) — request rejected |
| **Actual** | **200 OK** — request accepted, template returned |

Open `TC246_11` in the report (Full Response → "Open ↗") to see the exact request body, the response, and now the **Executed at** date/time.

---

## One-time Burp setup

1. Open **Burp Suite** → **Proxy** → **Options**: confirm the listener is `127.0.0.1:8080`.
2. In the browser you use for CKYC, set the HTTP proxy to `127.0.0.1:8080` (or use Burp's built-in browser).
3. Burp → **Proxy → Intercept is on**.
4. In CKYC, **log in normally** (so the browser holds a valid session, bearer token and CSRF token).

---

## Step 1 — capture a real, valid request

1. In CKYC, open **Email Notification → a scenario → Preview** (this is the screen that calls `previewEmailTemplate`).
2. Burp catches the request in **Proxy → Intercept**. It looks like:

   ```
   POST /ckyc-api-gateway/ckycBackend/email/previewEmailTemplate HTTP/1.1
   Host: iflowckyc.isolveapps.in
   Accept: application/json
   X-Requested-With: XMLHttpRequest
   Authorization: Bearer eyJhbGci...          <- real token
   X-CSRF-Token: 9f3c...                       <- real CSRF token
   Content-Type: application/json

   {"templateCode":"CKYC_TO_CBS_REVERSE","params":{},"userId":"546"}
   ```

3. Right-click the request → **Send to Repeater**, then switch to the **Repeater** tab. (Turn Intercept off so the app keeps working.)

---

## Step 2 — send it unchanged (baseline)

1. In **Repeater**, click **Send**.
2. You should get **HTTP 200** with the template JSON. This is the valid baseline.

---

## Step 3 — apply the negative (very large payload) and resend

1. In the Repeater request body, add one large extra field. Easiest way to make a big value in Burp:
   - select a short string, then use **Ctrl/Cmd+click** repeatedly, or
   - paste a long block of `A`s. A few hundred KB is enough to make the point; the automation uses ~1 MB.

   Change the body to:

   ```json
   {"templateCode":"CKYC_TO_CBS_REVERSE","params":{},"userId":"546","zzLargeField":"AAAAAAAA…(≈1 MB of A)"}
   ```

   (Burp → **Decoder** can generate a long string: type `A`, then use *Smart decode* / repeat, or paste from a text file.)

2. Click **Send**.

### What a correct (secure) API would do
Return **413 Content Too Large** or **400 Bad Request**, and **not** process the body.

### What this API actually does (the bug)
Returns **HTTP 200** with the normal template — it accepted a ~1 MB request with no size cap. That confirms the automation's finding. A stream of such requests could exhaust server memory (denial of service), which is why it is flagged **API4:2023**.

---

## Step 4 — record the evidence

1. In Repeater, note the **Request** (with the big field) and the **Response** (`200 OK`).
2. Right-click → **Save item**, or screenshot both panes.
3. Attach it to the bug `BUG-xxx` for `TC246_11` from the report's **Bug Report** sheet. The report already has the expected vs actual HTTP, the VAPT reference and the Executed-at time.

---

## Trying other negatives in the same Repeater tab

Change one thing and resend to confirm each report finding:

| Report check | Change in the body / request | Secure API should return |
|---|---|---|
| Missing mandatory field | delete `"templateCode":"…",` | 400 |
| Empty value | `"templateCode":""` | 400 |
| Only spaces | `"templateCode":"     "` | 400 |
| SQL injection | `"templateCode":"' OR '1'='1"` | 400 / not-found, no SQL error text |
| Script / XSS | `"templateCode":"<script>alert(1)</script>"` | 400, never echoed back unescaped |
| No session | delete the `Authorization` and `X-CSRF-Token` headers | 401 |
| Tampered token | change the last characters of the `Authorization` value | 401 |
| Wrong CSRF (write APIs only) | change the `X-CSRF-Token` value on a save/update/delete call | 403 |

If any of these returns **200** or a **500 with an exception / SQL message**, that is a real finding — the automation report already lists which ones did.

---

## Note on encrypted endpoints (most of CKYC)

Every non-`/email/` CKYC call sends an **encrypted envelope**:

```json
{"payload":"<Base64 of IV + AES-256-GCM ciphertext + tag>"}
```

with headers `clientsecret` (the AES key, RSA-wrapped) and `clientpublickey`. You **cannot** hand-edit the JSON fields in Burp, because the server only sees ciphertext. To test those endpoints manually:

- Drive the change from the **application screen** (enter the bad value in the UI and let the app encrypt it), then watch the response in Burp; or
- Replay what the automation already captured — the live console's **details panel** shows both the **decrypted** request/response (what to reason about) and the **encrypted** bytes (what went over the wire).

The automation does the tampering *before* encryption, which is why it can test these endpoints and Burp (by hand) cannot. Use Burp by hand for the plain-JSON `/email/` endpoints, and rely on the automation report for the encrypted ones.
