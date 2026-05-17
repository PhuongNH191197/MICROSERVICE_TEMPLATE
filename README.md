# Microservice Platform

Reusable Spring Boot microservices ecosystem. Single command startup.

## Architecture

```
                        ┌─────────────────┐
  Client ──────────────▶│   API Gateway   │:8080
                        │  (Rate Limit,   │
                        │   JWT Validate) │
                        └────────┬────────┘
                                 │ routes
          ┌──────────────────────┼──────────────────────┐
          ▼                      ▼                      ▼
   ┌─────────────┐      ┌──────────────┐      ┌──────────────┐
   │ auth-service│:8081 │ file-service │:8083 │user-profile  │:8091
   │             │      │   (MinIO)    │      │  -service    │
   └──────┬──────┘      └──────┬───────┘      └──────┬───────┘
          │                    │                      │
          └────────────────────┼──────────────────────┘
                               ▼
                        ┌─────────────────┐
                        │    RabbitMQ     │:5672
                        └────────┬────────┘
                                 ▼
                        ┌─────────────────┐
                        │ notification    │:8082
                        │   -service      │
                        └─────────────────┘

Infrastructure: Eureka:8761  Config:8888  Zipkin:9411
```

## Ports

| Service              | Port  |
|----------------------|-------|
| API Gateway          | 8080  |
| Auth Service         | 8081  |
| Notification Service | 8082  |
| File Service         | 8083  |
| User Profile Service | 8091  |
| Eureka Dashboard     | 8761  |
| Config Server        | 8888  |
| PostgreSQL           | 5432  |
| Redis                | 6379  |
| RabbitMQ             | 5672  |
| RabbitMQ Management  | 15672 |
| MinIO API            | 9000  |
| MinIO Console        | 9001  |
| Zipkin               | 9411  |

## Quick Start

```bash
# 1. Build all services
mvn clean package -DskipTests

# 2. Configure environment
cp .env.example .env
# Edit .env with your values

# 3. Start everything
docker-compose up -d

# 4. Verify — all services in Eureka
open http://localhost:8761
```

## Smoke Tests (curl)

```bash
BASE=http://localhost:8080

# Register
curl -X POST $BASE/api/auth/register \
  -H "Content-Type: application/json" \
  -d ''{"email":"test@test.com","password":"Test@1234","fullName":"Test User"}''

# Login — save access token
TOKEN=$(curl -s -X POST $BASE/api/auth/login \
  -H "Content-Type: application/json" \
  -d ''{"email":"test@test.com","password":"Test@1234"}'' | jq -r ''.data.accessToken'')

# Get my profile
curl $BASE/api/users/profile -H "Authorization: Bearer $TOKEN"

# Upload file
curl -X POST $BASE/api/files/upload \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/path/to/file.jpg"

# Check RabbitMQ
open http://localhost:15672  # guest:guest or your credentials
```

## Running Tests

```bash
# All tests
mvn test

# Single service
mvn test -pl infra-services/auth-service
```

## Adding a Business Service

1. Copy `business-services/user-profile-service` as template
2. Update `pom.xml` artifactId and port
3. Add module to root `pom.xml`
4. Add config to `infrastructure/config-server/src/main/resources/configs/`
5. Add route to `api-gateway` application.yml
6. Add service to `docker-compose.yml`
7. Listen to `UserRegisteredEvent` if user-scoped data needed

## Tech Stack

- Java 17 + Spring Boot 3.2.4 + Spring Cloud 2023.0.1
- PostgreSQL 16 + Flyway migrations
- RabbitMQ 3 (async events)
- MinIO (S3-compatible file storage)
- Redis 7 (rate limiting)
- JWT (jjwt 0.12.5), BCrypt strength 12
- Docker + docker-compose
