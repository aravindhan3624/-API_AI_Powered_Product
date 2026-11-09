# Product help - how to use the platform

## Create a project
Import an API source: Postman collection v2.1 JSON, OpenAPI 3 or Swagger 2 JSON, or a legacy Excel project. In Test Studio press Import, choose the file and a project name. Each import becomes a project with endpoints and test candidates. Imported and generated tests start as DRAFT.

## Test lifecycle
DRAFT then REVIEWED then APPROVED then ACTIVE. Only APPROVED and ACTIVE tests run by default; tick include DRAFT to try candidates. Editing the content of an approved test creates a new version and sends it back to DRAFT. Every change is written to the project history.

## Run tests
Select a project, choose tests (or all runnable), press Run. Progress is live. When finished open the HTML report or Excel from Reports. Reports are stored in reports/PROJECT/Reports_PROJECT_date_time.html with a history index.

## Environments and variables
Each project has environments with a base URL and variables. Use ${name} in paths, headers and bodies. Built-ins: ${NOW:yyyyMMddHHmmss}, ${DATE:-30:dd/MM/yyyy}, ${RANDOM:6}, ${UUID}, ${TXN_ID}, ${ENV:NAME}. Add a question mark for optional values, ${token?}. Alternatives: ${a|b}. Override at run time with -Dvar.name=value. Secrets: environment variables only.

## Authentication
Auth profiles: basic, bearer, apikey (header or query) and login (calls a login endpoint once, extracts the token and sends it on later calls). Cookie sessions are kept automatically. If credentials are missing the test is BLOCKED with the reason, not sent.

## Extraction and chaining
An endpoint can extract values from its response: JSON paths like data.token or responseList[0].id, $header.Name, jsonlist:path:max:offset for lists, and list.find{it.FIELD=='value'}.TARGET. Extracted values are available as ${name} in later tests.

## Outcomes
PASS, FAIL (a finding), BLOCKED (could not run, with reason), SKIPPED, NOT_EXECUTED. Pass rate counts only passed and failed.

## Security tests
Categories follow the rule pack: NO_SESSION, TAMPERED_TOKEN, TAMPERED_CSRF, WRONG_PASSWORD, OTHER_USER, TAMPERED_ENCRYPTION, WRONG_METHOD, LARGE_PAYLOAD, NOT_EXISTING and input validation cases. Expected codes and the OWASP reference come from the rule pack and can be overridden per test.

## The assistant
Ask in plain language: summarise the last run, why did TC012 fail, what is blocked, suggest negative tests for an endpoint, read this BRD and map the flow, explain an OWASP risk. The assistant works without any model or internet; when a local model such as Ollama is installed it only rephrases the answer. Suggested tests are always DRAFT and need your approval.

## Reports
HTML: module-wise, searchable by endpoint, base URL and API URL, details per test with redacted request and response. Excel: choose sheets Summary, Modules, All, Failures, Security.

## Is a BRD mandatory? How to write one
No. A BRD is optional: the product tests from a Postman collection or OpenAPI file plus test data alone. A BRD adds what a specification cannot say: the intended business flow, business rules, expected error behaviour, roles and limits. Use the templates in docs/templates: BRD_Template_Blank.docx (fill what you know, delete the rest) and BRD_Sample_Filled.docx (a worked example). Write one numbered line per flow step with METHOD /path (for example POST /auth/login), give every rule an id (BR-001) and use wording such as must, shall, cannot. Put API lists, field rules and role matrices in tables. Never write passwords, tokens or real customer data in it. Upload it in Studio under Documents and ask the assistant to analyse the BRD.

## Test data workbook (PROJECT_testdata.xlsx)
Open the project, go to Test Data and download PROJECT_testdata.xlsx. It is pre-filled from the imported API with one row per API: Method, Endpoint, Headers, Payload (JSON), ExpectedStatus, Extract, Set and Run, plus a Variables sheet. Replace the sample values with valid data that exists in your test environment, set Run to N for rows that must not run, and upload the file back. Secrets are never stored: type ${ENV:NAME} and set that environment variable. After the upload press Generate negatives: every negative and security test is derived from this valid data, one change per test.

## Where do I enter the application URL?
In the New project wizard, step 2 (Application URL), and at any time on the project Overview under Target application with the button Change URL (there is also a Check connection button that only tests that the address answers). The Run full assessment window shows the URL again so you always see where requests go. Studio sends requests only to that host.

## Where do I start? New API, existing project or demo
Demo: Home > Try the demo, then Run full assessment (public sandbox, nothing real is touched). New API: + New project (file, application URL, how to start), then Test data, Generate negatives, Run full assessment. Existing project: pick it in Current project, check the Target application on Overview, update Test data if the data changed, then Run approved (repeat the approved tests) or Run full assessment (also generates and runs negatives). The How do I page in the left menu explains each case step by step.

## Where do I enter credentials? Are they stored?
Open the project Overview and use the Credentials card (button Set credentials). It opens automatically after you create a project. Choose how the application signs in: a login API (user name and password; Studio calls it once and sends the returned token on every later call), a bearer token, HTTP Basic or an API key. What you type is kept in this PC's memory for the session, or encrypted on this PC (vault in your user profile) if you tick Remember on this PC. Passwords, tokens and keys are never written into the project file, the test-data workbook, the history or any report; the project holds only ${ENV:NAME} references. Passwords found in an imported file are moved out automatically. If a value is missing, the tests that need it are BLOCKED with the reason and Run full assessment asks you to enter it first. A web application with a one-time code (OTP) cannot be signed in by Studio: use Record from browser and log in yourself. Use a test account; login tests send only a few wrong passwords on purpose.

## Website address or API address? Verify my APIs and Find API address
The address you open in a browser is usually the website, not the API. The API address is the part that comes before your API paths, for example https://host/api. On the Overview, Target application, Verify my APIs asks the server (OPTIONS, and GET only on read-only paths; nothing is executed) whether your project's API paths exist there. A server that answers every path alike, even made-up ones, is a website: Studio says so and Run full assessment stops before generating or sending anything. Find API address tries the usual addresses (/api, /api/v1, the page path, and the base URLs defined inside your collection) and offers the one that works with Use as application URL.

## Can I import an API document (md, txt, docx, pdf)?
Yes. In the New project wizard choose the document. Studio reads lines such as POST /auth/login (also inside tables), takes the JSON block that follows as the sample data of that API, uses headings as modules and a Base URL line as the application URL. Endpoints without a visible body are reported, never invented. A Postman collection or OpenAPI file gives better results because it carries bodies, headers and examples.

## What is the live run console?
When a run starts, the Live run page opens by itself. It shows every request as it is sent (time, outcome, HTTP status, milliseconds, API, category, test), counters, pass rate, speed, a progress bar and a LIVE/OFFLINE indicator for the connection to Studio. Filter by failed, blocked or passed, click a line for the redacted request and response, cancel the run, and open the HTML, Excel, PDF and VAPT reports when it ends.

## How is the test data Excel file (workbook) created? How does Studio create the testdata.xlsx?
Studio reads the valid (positive) test of every API in the project: request body and headers from the collection, spec examples or document, expected 2xx status and the values extracted or set. It writes one row per API on the API_TestData sheet, lists every ${variable} on the Variables sheet and explains the columns on README. Secrets appear as ${ENV:PROJECT_NAME} references. You change only what is invalid for your environment and upload it: matching APIs are updated, new rows become new APIs, Run=N switches a row off, and an unchanged upload changes nothing.

## Which protocols and API types are supported?
REST (Postman, OpenAPI, Swagger, written API documents), SOAP/XML (WSDL import, XPath and SOAP Fault aware), GraphQL (introspection from a live endpoint or a file), WebSocket (connect, send, check the answer) and gRPC (unary methods discovered through reflection). Checks that do not apply to a protocol are not generated, for example wrong HTTP method for WebSocket or wrong field type for gRPC. Streaming gRPC methods are not supported yet.

## What is the Data flow page? How are ids passed between APIs?
Data flow finds values one API produces and another needs, for example an id returned by POST /pet and used in GET /pet/{petId}. Review the detected links and add them; you can also add your own. Runs then follow the order set up, test, clean up (delete what was created). Removing a link restores the original sample value.

## How do business rules become tests?
Upload a requirement document under Documents and open Business rules. Sentences such as required, at most N characters, one of A, B, C, between X and Y, response must contain, response time under N seconds become proposals with their source sentence. Anything unclear (which API or field, vague wording) is shown for you to decide and rules that cannot be tested are listed, never guessed. Nothing is created until you tick it, and new tests start as DRAFT.

## What is contract checking and spec drift?
For projects from an OpenAPI or Swagger file, every valid response is checked against its response contract; choose whether a deviation is ignored, passes with a remark or fails. Compare with a newer spec shows added, removed and changed APIs and applies the change safely: removed APIs have their tests parked as DRAFT, changed ones are tagged, nothing is deleted.

## Can I load test? Is it safe?
Yes, on the Load test page or with studio-cli load. Each virtual user signs in on its own and repeats the valid requests you tick. Limits are 100 users and 10 minutes; read-only requests are the default; Studio stops by itself when more than half of the recent requests fail; production-looking addresses need written confirmation. Use a test environment only. Results show throughput, p50 p90 p95 p99 latency and errors per API, with pass or fail limits.

## How do I run Studio from a build pipeline or on a schedule?
Automation page: scheduled runs while Studio is open, and the command line studio-cli run PROJECT --min-pass-rate 90 --junit file.xml with exit codes 0 passed, 1 findings or gate failed, 2 usage error, 3 target not reachable or not the API, 4 credentials missing. studio-cli export-ci writes Jenkins, GitHub Actions, Azure DevOps and Windows Task Scheduler files. Secrets come from environment variables.

## Can I export defects to Jira or Azure DevOps?
On the Reports page, Defects for your tracker exports the failures of the latest run as a CSV for Jira or Azure DevOps. Nothing is sent to either system. Failures are grouped per API and failure type by default; evidence is redacted.

## Is there a mock server? Is there an audit trail?
Yes. On the APIs page start a local mock server that answers your REST APIs from their contract, with optional latency and injected failures. History keeps an audit trail of every change and run with user and time; entries are chained by hash so a changed or removed line is detected, and it can be exported as CSV or JSON.

## How does team mode, sign-in and licensing work?
Team mode (-Dstudio.team=true) adds sign-in with roles admin, tester and viewer, per-user audit, lockout after repeated wrong passwords and HTTPS for network use. Without a licence Studio runs as a 30-day trial with up to five projects; install the licence from the left menu footer. Licences are checked offline against the vendor signature; an expired licence pauses runs and new projects but reports and exports keep working.

## Where do I click to run my tests? How do I run? Step by step
1. Open Home and press New project (or pick the project in Current project). 2. On Overview check the application URL (Verify my APIs must say API found). 3. Press Set credentials and enter the sign-in values (tick Remember on this PC). 4. Press Run full assessment (top right), tick the authorisation box and press Start assessment. 5. The Live run page opens by itself; watch it, then open Reports. Before the first run you may press Run approved on the Tests page to try only the tests that are already approved.

## What happens after I import my API? What are the next steps after import?
Importing only reads your file and creates the project; nothing is sent to your system yet. After import Studio opens the credentials form. Then: check the application URL on Overview, fill the Test data workbook with valid data (optional but recommended), generate or review the tests (Tests page), and press Run full assessment. The Overview card Next best step always names the one thing to do next.

## Why do I get Token Not Available, 401 or 422 on every request?
The server did not receive a token, so the sign-in did not work before that request. Common causes: the account is locked (the login answers 400 with a lock message), the password was not entered or was cleared when Studio restarted (tick Remember on this PC), the login ran for another project, or only one later test was run on its own without the login first. Run the whole project so the login step runs first, check the login line on the Live run page, and read its response. If the login says the account is locked, ask the administrator to unlock it before running again.

## What is the OTP step? Do I always need an OTP?
Only when the login answers that two-factor is on and sends an OTP session. If the login page configuration says twoFactorEnabled is 0, the Verify OTP step is not needed and Studio skips it without asking. When an OTP is needed, the Live run page shows an OTP window: type it and press Submit OTP, or Skip. Studio never reads or guesses an OTP.

## What does the live console show? What do the tiles and buttons mean?
Total is every planned test. Passed, Failed, Skipped (could not be sent: missing value or sign-in), Not executable (negative checks not run because the valid request of that API did not pass), Pass rate = passed divided by passed plus failed, Avg response, Overall time. Progress shows how many tests are done with time left and speed. Results shows a chart and positive/negative split. Modules shows each module; click one to filter. The execution map has one tile per test: green pass, red fail, amber skipped, grey not executable, blue running. Top failure reasons groups failures. Now executing shows the call in progress. The test table can be filtered and each row opens the request and response with secrets masked. Pause stops between two tests, TV is full screen, the moon button switches the theme. After the run the same page is saved as the report.

## How do I clear old records and start fresh?
Overview, Outcomes box, Clear old records (or Reports, Clear old records). It moves old run results and report files to reports/_archive and, if you tick the second box, removes the generated negative tests. Your APIs, data, credentials and history stay. Afterwards Overview shows no run yet.

## How do I save my credentials so I do not type them every time?
Open Set credentials, type the user name and password, keep Remember on this PC ticked and press Save credentials. The password is stored encrypted on this PC for your Windows user and is never written into the project file or a report. The Show button on the field lets you check what you typed. A project that signs in by itself (its login and OTP steps capture the token) keeps No sign-in needed and only needs the values listed under Sign-in details this project uses.

## My account is locked. What now?
The login answers 400 and says the account is locked after multiple failed attempts. Studio limits login negative tests to a few per run, but a wrong password entered earlier can still lock it. Ask the administrator to unlock the account, enter the correct password under Set credentials, and run again. Do not run the full assessment until the login test passes.

## What does each report contain? Where are my reports?
Reports page: the Report button opens the live-console style report of that run (the same numbers, map, table and request/response details, works offline). Classic is the earlier simple report. Excel has the summary, modules, all tests, failures and security sheets. The VAPT report lists security findings with OWASP references. Everything is in the reports folder under the project name; old runs are listed with their pass-rate change.

## What is the AI engine? Is it required? Does it slow my PC?
It is optional and OFF until you switch it on (left menu, AI engine). Studio works fully without it. When on, a local language model (Ollama, on this PC or on another machine whose address you type) only proposes test ideas and plain-English explanations; every idea waits for your approval and accepted ideas become fixed DRAFT tests, so runs do not vary. Gentle mode keeps requests small, uses few CPU threads and sends one request at a time. Nothing calls the model by itself. If a PC struggles with the model, run Ollama on another machine and put its address in the AI engine dialog.

## How do I get more test ideas? What is the Test ideas page?
Open Test ideas (step 2 - Build tests). Tick the APIs, press Find ideas. Built-in ideas use the meaning of each field (an e-mail without @, a mobile number with letters, a negative amount, a PAN with the wrong pattern). If the AI engine is on you can also tick Also ask the AI model. Every idea shows its value and why; tick the ones you want and press Add the ticked ideas as DRAFT tests. Ideas the model makes up about fields that do not exist are dropped automatically.

## How do I get a plain-English explanation of a failure?
Open the test (Tests page, click the row, tab Last result) and press Explain in plain English. You get what happened, the likely cause and what to do. If the AI engine is on, a short model wording is added below; the built-in explanation always stays.

## What does auto-heal do when the specification changes?
APIs, Compare with a newer spec. Besides the list of added, removed and changed APIs, Studio proposes repairs to your valid requests: fields that were added or removed, fields whose type changed, new required query parameters or headers. You tick what may change; values you entered for fields that still exist are never overwritten; nothing is deleted.

## Can I export my tests as Karate or Rest Assured code?
Yes: Automation and export, Export the tests as code. Karate gives feature files with a karate-config.js; Rest Assured gives a Maven project with TestNG classes (it compiles and runs with mvn test). Only approved and active tests are exported unless you tick include drafts. Secrets are never written; they are read from environment variables. WebSocket and gRPC tests are not exported.

## What do Tests, Auth and Contract mean on the APIs page? What are Base URL and API path?
Tests is how many tests that API has (valid plus negative and security tests). Auth is the sign-in profile Studio applies; empty means none from Studio because the request brings its own token header or needs none. Contract says yes when the response schema of an OpenAPI or Swagger file is stored and every valid response is checked against it; collections, Excel and documents have no schema. Every API is called at Base URL plus API path; variables such as api_base_url are filled in on the page.

## How do I change the address of one API or add a missing base URL?
APIs page, Edit address on the row. Paste the full URL and Studio splits it into base URL and API path (you can also type them separately). Tick Also make this the base URL for the whole project when all APIs live on that server, and tick Run this API's valid test right after saving to see the result at once. An API on another host keeps its own full URL and that host is added to the allowed hosts.
