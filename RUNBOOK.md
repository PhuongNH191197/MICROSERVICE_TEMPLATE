# Runbook — Microservice Platform

## Prerequisites

| Tool | Version | Check |
|------|---------|-------|
| Java | 17+ | `java -version` |
| Maven | 3.9+ | `mvn -version` |
| Docker Desktop | Latest | `docker --version` |
| Docker Compose | 2.x+ | `docker compose version` |
| Git | Any | `git --version` |

Docker Desktop must be **running** before any docker commands.

---

## Start Full System

```bash
# 1. Build all JARs (first time or after code changes)
cd D:\MICROSERVICE_TEMPLATE
mvn clean package -DskipTests

# 2. Configure environment (first time only)
copy .env.example .env
# Edit .env — set JWT_SECRET to a 64-char base64 string at minimum

# 3. Start all services
docker-compose up -d

# 4. Watch startup logs
docker-compose logs -f

# 5. Check all containers healthy (~90 seconds)
docker-compose ps
```

Expected output of `docker-compose ps` when healthy:
```
NAME                   STATUS
postgresql             healthy
redis                  healthy
rabbitmq               healthy
minio                  healthy
zipkin                 healthy
eureka-server          healthy
config-server          healthy
api-gateway            healthy
auth-service           healthy
notification-service   healthy
file-service           healthy
user-profile-service   healthy
```

---

## Start Single Service (Development Mode)

Run one service locally while everything else is in Docker:

```bash
# Start infrastructure in Docker (exclude the service you want to develop)
docker-compose up -d postgresql redis rabbitmq minio zipkin eureka-server config-server

# Run your service locally (hot-reload)
cd infra-services/auth-service
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

For this to work, the service's `application.yml` must have a `local` profile pointing to `localhost` instead of Docker hostnames.

---

## Stop Services

```bash
# Stop all, keep volumes (data preserved)
docker-compose stop

# Stop all and remove containers (data preserved in volumes)
docker-compose down

# Stop all and remove containers + volumes (data DELETED)
docker-compose down -v
```

---

## Useful URLs

| Service | URL | Credentials |
|---------|-----|-------------|
| API Gateway | http://localhost:8080 | — |
| Eureka Dashboard | http://localhost:8761 | eureka / secret |
| RabbitMQ Management | http://localhost:15672 | rabbit_user / rabbit_pass |
| MinIO Console | http://localhost:9001 | minio_admin / minio_pass123 |
| Zipkin Traces | http://localhost:9411 | — |
| Config Server | http://localhost:8888 | — |

Check credentials in your `.env` file if they differ from defaults.

---

## Check Service Health

```bash
# All services at once
docker-compose ps

# Single service health endpoint
curl http://localhost:8081/actuator/health   # auth-service
curl http://localhost:8082/actuator/health   # notification-service
curl http://localhost:8083/actuator/health   # file-service
curl http://localhost:8091/actuator/health   # user-profile-service
curl http://localhost:8080/actuator/health   # api-gateway
curl http://localhost:8761/actuator/health   # eureka-server
```

---

## View Logs

```bash
# All services
docker-compose logs -f

# Single service
docker-compose logs -f auth-service
docker-compose logs -f api-gateway
docker-compose logs -f notification-service
docker-compose logs -f file-service
docker-compose logs -f user-profile-service

# Last N lines
docker-compose logs --tail=100 auth-service

# Since timestamp
docker-compose logs --since="2024-01-01T12:00:00" auth-service
```

---

## Connect to PostgreSQL

```bash
# Via Docker exec
docker exec -it postgresql psql -U platform_user -d auth_db

# Via psql client (if installed locally)
psql -h localhost -p 5432 -U platform_user -d auth_db

# Useful queries
\l           -- list databases
\dt          -- list tables
\d users     -- describe table
SELECT * FROM users LIMIT 10;
SELECT * FROM refresh_tokens WHERE revoked = false;
```

Databases: `auth_db`, `notification_db`, `file_db`, `profile_db`

---

## Rebuild After Code Change

```bash
# Rebuild a single service
mvn clean package -DskipTests -pl infra-services/auth-service

# Rebuild Docker image and restart container
docker-compose up -d --build auth-service

# Rebuild all and restart everything
mvn clean package -DskipTests
docker-compose up -d --build
```

---

## Restart Single Service

```bash
# Restart without rebuilding
docker-compose restart auth-service

# Stop, remove, and re-create (picks up .env changes)
docker-compose stop auth-service
docker-compose rm -f auth-service
docker-compose up -d auth-service
```

---

## Common Errors and Fixes

### Service not registering to Eureka

**Symptom:** Service not visible in Eureka dashboard at http://localhost:8761

**Checks:**
```bash
curl http://localhost:8761/actuator/health
docker-compose logs auth-service | grep -i eureka
cat .env | grep EUREKA
```

**Fix:** Ensure `EUREKA_SERVER_URL=http://eureka:secret@eureka-server:8761/eureka/` matches Eureka basic auth credentials in `.env`.

---

### RabbitMQ connection refused

**Symptom:** `Connection refused to rabbitmq:5672` in service logs

**Checks:**
```bash
docker-compose ps rabbitmq
docker-compose logs rabbitmq
```

**Fix:**
```bash
docker-compose restart rabbitmq
docker-compose restart auth-service notification-service file-service user-profile-service
```

Verify credentials in `.env` match `RABBITMQ_DEFAULT_USER` / `RABBITMQ_DEFAULT_PASS`.

---

### JWT token invalid / 401 on all requests

**Symptom:** Every authenticated request returns `401 Unauthorized`

**Checks:**
```bash
cat .env | grep JWT_SECRET
```

**Fix:** Set `JWT_SECRET` to a base64 string of at least 64 characters:
```powershell
# PowerShell — generate strong secret
[Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(48))
```

Then restart:
```bash
docker-compose restart auth-service api-gateway
```

---

### File upload fails

**Symptom:** `POST /api/files/upload` returns 500 or connection error

**Checks:**
```bash
docker-compose ps minio
docker-compose logs minio
curl http://localhost:9000/minio/health/live
```

**Fix:**
```bash
docker-compose restart minio
docker-compose restart file-service
```

---

### Database migration fails (Flyway error)

**Symptom:** Service fails to start with `FlywayException: Validate failed`

**Cause:** Migration checksum mismatch — migration file edited after running.

**Fix (dev only — NEVER in production):**
```sql
docker exec -it postgresql psql -U platform_user -d auth_db
DROP SCHEMA public CASCADE;
CREATE SCHEMA public;
\q
```

Then restart the service:
```bash
docker-compose restart auth-service
```

---

### Config Server unreachable

**Symptom:** Service fails to start with `Could not locate PropertySource`

**Checks:**
```bash
curl http://localhost:8888/actuator/health
docker-compose logs config-server
```

**Fix:**
```bash
docker-compose restart config-server
# Wait 15 seconds
docker-compose restart auth-service
```

---

### Out of memory / slow startup

**Symptom:** Services take >3 minutes to start or containers die with OOMKilled

**Fix:** Increase Docker Desktop memory to at least 4 GB:
Docker Desktop → Settings → Resources → Memory → 4 GB

---

## Database Backup and Restore

```bash
# Backup
docker exec postgresql pg_dump -U platform_user auth_db > auth_db_backup.sql

# Restore
docker exec -i postgresql psql -U platform_user auth_db < auth_db_backup.sql
```

---

## View Distributed Traces (Zipkin)

1. Open http://localhost:9411
2. Select a service from the dropdown
3. Click "Run Query"
4. Click any trace to see the full call chain
