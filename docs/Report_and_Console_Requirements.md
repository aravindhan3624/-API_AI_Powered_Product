# Report & Console Requirements (from user, 2026-10-16)

Reference: current console screenshot (CKYC run: 1737 total, 1181 passed, 178 failed, 4 skipped, 374 not executable, 86.9% pass rate, 376 ms avg, 11m 12s). The existing legacy report already saves to `reports/<PROJECT>/Reports_<PROJECT>_<dd-MMM-yy>_<HH-mm>.html` — keep this convention in the product.

## Requirements (target: Phase 2 report work, then console in Phase 3)
| # | Requirement | Acceptance |
|---|---|---|
| R1 | **Project-wise report location**: `reports/<project>/` with name `Reports_<project>_<date>_<time>.html` (+ Excel with same stem) | Two runs of different projects never overwrite each other; sorted by time |
| R2 | **Clickable, navigable, openable**: after a run the console shows a button **"View Report"** (opens the saved HTML) and a link to the report folder; a **Reports history** page lists all runs per project with date, counts, pass rate and Open/Excel buttons | One click from console or history opens the HTML |
| R3 | Report works **offline** as a single HTML file (no server, no CDN) and can be opened later or emailed | Open from disk with no network |
| R4 | **Module-wise view**: tests grouped by module/folder (from Postman folders, OpenAPI tags or Excel Module column) with per-module totals, collapsible, filter by module | Module list + counts match totals |
| R5 | **Search box**: find by endpoint, API name, test case id, **base URL, full API URL**, HTTP method, status, category, outcome | Typing a URL fragment filters instantly |
| R6 | Show **Base URL, environment, full request URL, method, headers/body (redacted), response, status, latency, expected vs actual, remark/OWASP** per test | Detail panel for each test |
| R7 | Live console keeps: state OFFLINE/LIVE separate from run status; "run ended, showing final state" banner with saved-report path as a clickable link | Link opens report |
| R8 | Customisable Excel: choose sheets (Summary, Module-wise, Failures, Security, All tests) and columns | Template/column picker |
| R9 | Light/Dark and TV mode retained | Existing console features preserved |

## Note
The legacy console's "NOT EXECUTABLE 374" (blocked tests) must remain a separate outcome (maps to `BLOCKED`/`NOT_EXECUTED` in result contract v1).
