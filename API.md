# API Reference

## Base URL

```
http://localhost:8080
```

All requests go through the API Gateway. The Gateway validates JWT and forwards `X-User-Id`, `X-User-Email`, `X-User-Roles` headers to downstream services.

---

## Authentication Flow

### 1. Register

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com",
    "password": "Password123!",
    "fullName": "Jane Doe"
  }'
```

Response `200`:
```json
{
  "success": true,
  "message": "User registered successfully",
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "email": "user@example.com",
    "fullName": "Jane Doe"
  }
}
```

### 2. Login and get tokens

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com",
    "password": "Password123!"
  }'
```

Response `200`:
```json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGciOiJIUzUxMiJ9...",
    "refreshToken": "eyJhbGciOiJIUzUxMiJ9...",
    "tokenType": "Bearer",
    "expiresIn": 900
  }
}
```

Save the `accessToken` for subsequent requests.

### 3. Use access token

```bash
TOKEN="eyJhbGciOiJIUzUxMiJ9..."

curl http://localhost:8080/api/users/profile \
  -H "Authorization: Bearer $TOKEN"
```

### 4. Refresh token

```bash
curl -X POST http://localhost:8080/api/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{
    "refreshToken": "eyJhbGciOiJIUzUxMiJ9..."
  }'
```

Response `200`: same shape as Login. Old refresh token is revoked.

### 5. Logout

```bash
curl -X POST http://localhost:8080/api/auth/logout \
  -H "Content-Type: application/json" \
  -d '{
    "refreshToken": "eyJhbGciOiJIUzUxMiJ9..."
  }'
```

Response `200`:
```json
{ "success": true, "message": "Logged out successfully" }
```

---

## Auth Service Endpoints

Base path: `/api/auth`

### POST /api/auth/register

**Request:**
```json
{
  "email": "string (required, valid email)",
  "password": "string (required, min 8 chars, 1 uppercase, 1 number)",
  "fullName": "string (required, max 100 chars)"
}
```

| Status | Meaning |
|--------|---------|
| 200 | Registered, UserRegisteredEvent published |
| 400 | Validation error or email already exists |

---

### POST /api/auth/login

**Request:**
```json
{ "email": "string", "password": "string" }
```

| Status | Meaning |
|--------|---------|
| 200 | Tokens returned |
| 400 | Invalid credentials |
| 403 | Account disabled |

---

### POST /api/auth/refresh

**Request:**
```json
{ "refreshToken": "string" }
```

| Status | Meaning |
|--------|---------|
| 200 | New token pair, old token revoked |
| 401 | Token expired or revoked |

---

### POST /api/auth/logout

**Request:**
```json
{ "refreshToken": "string" }
```

Response: `200` always (idempotent).

---

### GET /api/auth/me

**Headers:** `Authorization: Bearer <token>`

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "id": "uuid",
    "email": "user@example.com",
    "fullName": "Jane Doe",
    "roles": ["ROLE_USER"],
    "enabled": true
  }
}
```

---

### POST /api/auth/validate

Internal endpoint called by API Gateway only. Not for direct client use.

**Request:**
```json
{ "token": "string" }
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "userId": "uuid",
    "email": "user@example.com",
    "roles": ["ROLE_USER"]
  }
}
```

---

### POST /api/auth/forgot-password

```bash
curl -X POST http://localhost:8080/api/auth/forgot-password \
  -H "Content-Type: application/json" \
  -d '{ "email": "user@example.com" }'
```

Response `200` always (does not confirm if email exists). Publishes PasswordResetEvent to notification-service.

---

### POST /api/auth/reset-password

```bash
curl -X POST http://localhost:8080/api/auth/reset-password \
  -H "Content-Type: application/json" \
  -d '{
    "resetToken": "token-from-email",
    "newPassword": "NewPassword123!"
  }'
```

| Status | Meaning |
|--------|---------|
| 200 | Password updated |
| 400 | Token expired or invalid |

---

## File Service Endpoints

Base path: `/api/files`
All endpoints require `Authorization: Bearer <token>`.

### POST /api/files/upload

```bash
curl -X POST http://localhost:8080/api/files/upload \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/path/to/document.pdf"
```

**Constraints:** max 10 MB, allowed types: jpg, jpeg, png, pdf, docx

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "fileId": "uuid",
    "originalName": "document.pdf",
    "url": "http://localhost:9000/documents/stored-name.pdf?X-Amz-...",
    "size": 204800,
    "contentType": "application/pdf",
    "createdAt": "2024-01-01T12:00:00Z"
  }
}
```

| Status | Meaning |
|--------|---------|
| 200 | Uploaded, FileUploadedEvent published |
| 400 | File too large or type not allowed |
| 401 | Missing or invalid token |

---

### GET /api/files/{fileId}

Returns a presigned URL redirect valid for 1 hour.

```bash
curl -L http://localhost:8080/api/files/uuid-here \
  -H "Authorization: Bearer $TOKEN"
```

| Status | Meaning |
|--------|---------|
| 302 | Redirect to presigned MinIO URL |
| 404 | File not found or deleted |
| 403 | File belongs to another user |

---

### DELETE /api/files/{fileId}

Soft delete — marks `deleted=true` in DB. File remains in MinIO.

```bash
curl -X DELETE http://localhost:8080/api/files/uuid-here \
  -H "Authorization: Bearer $TOKEN"
```

**Response `200`:**
```json
{ "success": true, "message": "File deleted" }
```

---

### GET /api/files/user/{userId}

List all non-deleted files for a user (paginated).

```bash
curl "http://localhost:8080/api/files/user/uuid-here?page=0&size=20" \
  -H "Authorization: Bearer $TOKEN"
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "content": [{ "fileId": "...", "originalName": "...", "size": 1024 }],
    "totalElements": 5,
    "totalPages": 1,
    "page": 0,
    "size": 20
  }
}
```

---

## User Profile Endpoints

Base path: `/api/users`
All endpoints require `Authorization: Bearer <token>`.

### GET /api/users/profile

```bash
curl http://localhost:8080/api/users/profile \
  -H "Authorization: Bearer $TOKEN"
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "id": "uuid",
    "fullName": "Jane Doe",
    "avatarUrl": null,
    "bio": null,
    "phone": null,
    "address": null,
    "createdAt": "2024-01-01T12:00:00Z",
    "updatedAt": "2024-01-01T12:00:00Z"
  }
}
```

---

### PUT /api/users/profile

```bash
curl -X PUT http://localhost:8080/api/users/profile \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "fullName": "Jane Smith",
    "bio": "Software Engineer",
    "phone": "+84901234567",
    "address": "Ho Chi Minh City, Vietnam"
  }'
```

Response `200`: Updated profile object (same shape as GET).

---

### POST /api/users/avatar

Upload avatar — delegates to file-service via Feign Client.

```bash
curl -X POST http://localhost:8080/api/users/avatar \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@avatar.jpg"
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "avatarUrl": "http://localhost:9000/avatars/uuid.jpg?X-Amz-..."
  }
}
```

---

## Error Response Format

All errors use this envelope:

```json
{
  "success": false,
  "message": "Human-readable error description",
  "errors": [
    { "field": "email", "message": "must be a valid email address" }
  ],
  "timestamp": "2024-01-01T12:00:00Z",
  "path": "/api/auth/register"
}
```

`errors` array is populated for validation failures (400). Empty for other error types.

---

## HTTP Status Codes

| Code | Meaning |
|------|---------|
| 200 | Success |
| 400 | Bad request, validation error, or duplicate resource |
| 401 | Missing, expired, or invalid JWT |
| 403 | Valid JWT but insufficient roles, or accessing another user's resource |
| 404 | Resource not found |
| 429 | Rate limit exceeded (100 req/min/IP) |
| 500 | Unexpected server error |

---

## PowerShell Examples (Windows)

```powershell
# Register
Invoke-RestMethod -Method POST -Uri "http://localhost:8080/api/auth/register" `
  -ContentType "application/json" `
  -Body '{"email":"test@test.com","password":"Password123!","fullName":"Test User"}'

# Login and save token
$response = Invoke-RestMethod -Method POST -Uri "http://localhost:8080/api/auth/login" `
  -ContentType "application/json" `
  -Body '{"email":"test@test.com","password":"Password123!"}'
$TOKEN = $response.data.accessToken

# Get profile
Invoke-RestMethod -Uri "http://localhost:8080/api/users/profile" `
  -Headers @{ Authorization = "Bearer $TOKEN" }

# Upload file
$form = @{ file = Get-Item "C:\path\to\file.jpg" }
Invoke-RestMethod -Method POST -Uri "http://localhost:8080/api/files/upload" `
  -Headers @{ Authorization = "Bearer $TOKEN" } `
  -Form $form
```
