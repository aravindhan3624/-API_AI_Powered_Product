# How to use the API document template

Do NOT upload this guide. Upload a copy of `API_Document_Template.md` (or the same content as a Word .docx or a text-based PDF) with your own APIs.

## What Studio reads (and what it ignores)

| In your document | What Studio does |
|---|---|
| A line `Base URL: https://host/path` | Becomes the application URL of the project. If missing, the first web address in the document is used, else you are asked to type it. |
| A heading `# Title` or `## Title` (or a numbered line such as `1. Title`) | Becomes the module (group) name for the APIs below it. Headings with `###` are ignored. |
| A line containing `GET`, `POST`, `PUT`, `PATCH` or `DELETE` followed by a path that starts with `/` (also inside a table) | Creates one API. The text after the path on the same line (after a `-`) becomes the API name. |
| `{customerId}` inside a path | Becomes a variable `${customerId}` you can fill in the Test data sheet. |
| The first `{ ... }` JSON block right after that line (inside a code fence or plain) | Becomes the request body. Must be valid JSON with straight quotes. |
| Anything else (response samples, field tables, notes) | Not turned into tests. Kept for people. |

Each API is created as one positive test in DRAFT state. Studio never invents data: an API without a body is created without one and listed as a warning.

## Rules for a clean import
1. One API per line: method, a space, the path, then ` - Name`.
2. Put the request body JSON directly under that line, before the next API line.
3. Never write real passwords or tokens. Use `${ENV:NAME}` and set the real value in Studio under Credentials (you type it yourself).
4. Use sample, non-customer data in bodies.
5. Headers and login/token capture are set in Studio under Credentials, not in this document.
6. Delete any example block you do not need. A leftover example API would be imported as a real one.

## What happens next
1. Import the file in Studio: the project is created with the APIs listed.
2. Studio makes a test-data workbook (`<Project>_testdata.xlsx`) pre-filled from your bodies.
3. You correct the valid data in the workbook and upload it back.
4. Studio generates the negative and security tests from those valid examples; you run them from the Run page.

## Business rules
The Fields table in the template is for people. To turn written business rules into reviewable tests, use the BRD templates (`BRD_Template_Blank.docx`, `BRD_Sample_Filled.docx`) in the same folder.
