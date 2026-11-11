# API Document
Application: Demo Bank Customer API
Base URL: https://api.example-bank.com/v1
Version: 1.0

## Authentication

POST /auth/login - Login and get token
```json
{
  "username": "${ENV:API_USER}",
  "password": "${ENV:API_PASSWORD}"
}
```
Response sample:
```json
{ "status": "SUCCESS", "token": "<returned by the server>" }
```

## Customer

POST /customers - Create customer
```json
{
  "firstName": "Asha",
  "lastName": "Kumar",
  "mobile": "9000000001",
  "email": "asha.kumar@example.com",
  "dateOfBirth": "1990-05-21"
}
```
Response sample:
```json
{ "status": "SUCCESS", "customerId": "C100045" }
```

GET /customers/{customerId} - Get customer by id

PUT /customers/{customerId} - Update customer
```json
{
  "mobile": "9000000002",
  "email": "asha.kumar@example.com"
}
```

DELETE /customers/{customerId} - Delete customer

## Fields (for people; Studio does not read this table yet)
| Field | Type | Required | Rule |
|---|---|---|---|
| firstName | text | yes | letters only, 2 to 50 characters |
| mobile | text | yes | exactly 10 digits, starts with 6 to 9 |
| email | text | no | valid e-mail format |
| dateOfBirth | date | yes | yyyy-mm-dd, customer must be 18 or older |
