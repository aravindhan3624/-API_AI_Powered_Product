# Free public APIs for product testing (kept for the "upload and see" test)

Downloaded specs are in `specs/` (JSON data only, never executed). Downloaded 2026-10-16 with certificate validation on.

| API | Base URL | Spec / collection | Auth | Good for |
|---|---|---|---|---|
| Swagger Petstore v3 | https://petstore3.swagger.io/api/v3 | `specs/Petstore_v3_openapi.json` (OpenAPI 3.0.4, 13 paths) | API key / none for most | OpenAPI import, CRUD, enums, negative IDs |
| Swagger Petstore v2 | https://petstore.swagger.io/v2 | `specs/Petstore_v2_swagger.json` (Swagger 2.0, 14 paths) | `api_key: special-key` | Swagger 2 import |
| httpbin | https://httpbin.org | `specs/httpbin_swagger.json` (Swagger 2.0, 52 paths) | Basic/Bearer test endpoints | Status codes, headers, auth, redirects, delays, method tests |
| DummyJSON | https://dummyjson.com | none official (model in `projects/DEMO/model.json`) | Login -> Bearer (`emilys`/`emilyspass`, public) | Auth chaining, CRUD, token negatives |

Not downloaded (no official spec file; add a manual project or Postman collection later):
JSONPlaceholder `https://jsonplaceholder.typicode.com` (posts/comments, no auth) ·
ReqRes `https://reqres.in` (users/login) ·
Fake Store `https://fakestoreapi.com` (products/carts/login) ·
Restful-Booker `https://restful-booker.herokuapp.com` (token + booking CRUD) ·
Postman Echo `https://postman-echo.com` (echo/auth/headers) ·
Open-Meteo `https://api.open-meteo.com` (read-only weather) ·
PokeAPI `https://pokeapi.co/api/v2` (read-only).

Notes: public services can change, rate-limit or be offline; writes are usually simulated (not persisted). Use only for functional tests, never load tests.
