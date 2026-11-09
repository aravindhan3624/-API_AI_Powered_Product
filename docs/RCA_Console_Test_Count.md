# RCA: Live console total changed during the run (≈1500 at start, ≈1200 at end)

| | |
|---|---|
| **Project** | CKYC 4.0 API Automation |
| **Reported by** | QA team (live console users) |
| **Analysed / fixed by** | Aravindhan D |
| **Date** | 12 Oct 2026 |
| **Severity** | Low: reporting/display only. No test result was wrong and no test was lost. |
| **Status** | Fixed and verified |

## 1. Problem
When a CKYC run started, the live console showed **Total ≈ 1500** test cases. During the run the number went **down**, and at the end it showed **≈ 1200**. The Excel report showed a different total again. The team could not tell which number was correct, or why tests "disappeared".

## 2. Root cause
1. **The full test plan is built at the start.** Before the first API is called, the runner builds every test: 114 positive tests plus about 1,390 negative checks generated from them (15-20 per API). The console counted all of them, so it showed ≈ 1500.
2. **Negatives depend on their positive test.** Since 11 Oct, an API's negative checks run only if its positive test showed the endpoint works. When the positive gets **404** (endpoint not deployed on DevHost) or **403** (no permission / feature disabled), its negatives cannot prove anything: a "rejection" from an endpoint that does not exist is not a real validation. So these checks are cancelled. This is correct behaviour.
3. **The defect:** the console **removed** the cancelled checks from its list instead of keeping and marking them. Every time an API's positive failed with 404/403, 15-20 tests vanished and the total shrank. There was no on-screen explanation.
4. **The Excel report** wrote one summary row per cancelled API but counted it as 1, so its total matched neither number.

**In short:** the total showed "tests still planned", not "tests in the plan", and cancelled checks were hidden instead of reported.

## 3. Impact
- No functional impact. Every executed test was judged correctly, and the cancelled checks are not executable.
- Confusing numbers between the console, the Excel report and the IntelliJ/Maven summary.

## 4. Fix (12 Oct 2026)
| Area | Change |
|---|---|
| Live console | Cancelled checks **stay in the total**. They are shown in grey as **"Not executable"** with the reason, e.g. *"Positive test TC064 failed with HTTP 404 (endpoint not deployed)"*. The total no longer changes during a run. |
| Excel report | A "Not executable" row counts as the number of checks it stands for. **Total = Passed + Failed + Skipped + Not executable**, the same as the console. |
| IntelliJ / Maven summary line | Prints the same breakdown: `Total | PASS | FAIL | SKIP | NOT EXECUTABLE`. |

TestNG's own `Tests run:` line still counts only tests that were actually executed. That is TestNG behaviour, not an error.

## 5. Verification
Run of modules 04 (Audit, which contains not-deployed endpoints) and 07 (Users):

| Moment | Console total | Not executable |
|---|---|---|
| Start | 384 | 40 |
| Mid-run | 384 | 131 |
| End | 384 | 140 |
| Excel Summary | **384** = 205 pass + 35 fail + 4 skip + 140 not executable | |

## 6. Prevention
- Totals in the console, Excel and the Maven summary are now computed with the same rule.
- Every cancelled or skipped check shows its reason in the console's **Remarks** column and in the Excel **Failure Category / Why** columns.
- When the missing endpoints are deployed on DevHost, their negatives run automatically and "Not executable" goes down. No test data change is needed.
