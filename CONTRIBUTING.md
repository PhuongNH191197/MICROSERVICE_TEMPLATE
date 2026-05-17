# Contributing Guide

## How to Add a New Business Service

### Step 1 — Scaffold the module

```
business-services/
└── your-service/
    ├── pom.xml
    └── src/
        ├── main/
        │   ├── java/com/platform/yourservice/
        │   │   ├── YourServiceApplication.java
        │   │   ├── config/
        │   │   │   └── RabbitMQConfig.java       (if consuming events)
        │   │   ├── controller/
        │   │   │   └── YourController.java
        │   │   ├── service/
        │   │   │   ├── YourService.java           (interface)
        │   │   │   └── YourServiceImpl.java
        │   │   ├── repository/
        │   │   │   └── YourRepository.java        (extends JpaRepository)
        │   │   ├── entity/
        │   │   │   └── YourEntity.java
        │   │   ├── dto/
        │   │   │   ├── YourRequest.java
        │   │   │   └── YourResponse.java
        │   │   ├── mapper/
        │   │   │   └── YourMapper.java
        │   │   └── exception/
        │   │       └── GlobalExceptionHandler.java
        │   └── resources/
        │       ├── application.yml                (minimal — import from config server)
        │       └── db/migration/
        │           └── V1__init.sql
        └── test/
            └── java/com/platform/yourservice/
                └── YourServiceTest.java
```

### Step 2 — pom.xml (copy from user-profile-service, change artifactId)

```xml
<artifactId>your-service</artifactId>
<name>your-service</name>
```

### Step 3 — application.yml (minimal bootstrap)

```yaml
spring:
  application:
    name: your-service
  config:
    import: optional:configserver:${CONFIG_SERVER_URL:http://localhost:8888}
server:
  port: 809X   # pick unused port
```

### Step 4 — Add to root pom.xml

```xml
<modules>
  ...
  <module>business-services/your-service</module>
</modules>
```

### Step 5 — Add config file

Create `infrastructure/config-server/src/main/resources/configs/your-service.yml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${POSTGRES_HOST:localhost}:5432/your_db
    username: ${POSTGRES_USER}
    password: ${POSTGRES_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    locations: classpath:db/migration
eureka:
  client:
    service-url:
      defaultZone: ${EUREKA_SERVER_URL}
```

### Step 6 — Add Gateway route

In `infrastructure/api-gateway/src/main/resources/application.yml`:

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: your-service
          uri: lb://your-service
          predicates:
            - Path=/api/your/**
```

### Step 7 — Add to docker-compose.yml

```yaml
your-service:
  build: ./business-services/your-service
  ports:
    - "809X:809X"
  environment:
    - POSTGRES_HOST=postgresql
    - POSTGRES_USER=${POSTGRES_USER}
    - POSTGRES_PASSWORD=${POSTGRES_PASSWORD}
    - EUREKA_SERVER_URL=http://eureka:${EUREKA_PASSWORD}@eureka-server:8761/eureka/
    - CONFIG_SERVER_URL=http://config-server:8888
  depends_on:
    postgresql:
      condition: service_healthy
    eureka-server:
      condition: service_healthy
    config-server:
      condition: service_healthy
  healthcheck:
    test: ["CMD", "curl", "-f", "http://localhost:809X/actuator/health"]
    interval: 30s
    timeout: 10s
    retries: 5
```

### Step 8 — Build and verify

```bash
mvn clean package -DskipTests
docker-compose up -d --build your-service
# Check it appears in Eureka: http://localhost:8761
```

---

## Package Structure Convention

```
com.platform.{servicename}.
├── config/          Spring @Configuration classes, beans
├── controller/      REST controllers (@RestController)
├── service/         Business logic interface + impl
├── repository/      JPA repositories
├── entity/          JPA @Entity classes
├── dto/             Request/Response DTOs (Lombok @Data)
├── mapper/          Entity <-> DTO mapping
├── event/           RabbitMQ event listeners (@RabbitListener)
├── client/          Feign clients (@FeignClient)
└── exception/       GlobalExceptionHandler (@RestControllerAdvice)
```

---

## Naming Conventions

| What | Convention | Example |
|------|-----------|---------|
| Classes | PascalCase | `UserProfileService` |
| Interfaces | PascalCase | `UserProfileRepository` |
| Methods | camelCase | `findByUserId()` |
| Variables | camelCase | `accessToken` |
| Constants | UPPER_SNAKE_CASE | `MAX_RETRY_COUNT` |
| DB tables | snake_case | `user_profiles` |
| DB columns | snake_case | `created_at` |
| REST paths | kebab-case | `/api/user-profiles` |
| Maven artifacts | kebab-case | `user-profile-service` |
| Config files | kebab-case | `user-profile-service.yml` |
| Packages | lowercase | `com.platform.userprofile` |

---

## Git Branch Strategy

```
main          <- production-ready code only
develop       <- integration branch, all features merge here
feature/*     <- new features (branch from develop)
hotfix/*      <- urgent fixes (branch from main, merge to both main + develop)
release/*     <- release preparation (branch from develop)
```

**Branch naming:**
```
feature/auth-service-password-policy
feature/order-service-init
hotfix/jwt-token-expiry-bug
release/v1.1.0
```

---

## Commit Message Format (Conventional Commits)

```
<type>(<scope>): <short description>

<optional body>

<optional footer>
```

**Types:**

| Type | When |
|------|------|
| `feat` | New feature or endpoint |
| `fix` | Bug fix |
| `docs` | Documentation only |
| `refactor` | Code change, no feature or fix |
| `test` | Adding or fixing tests |
| `chore` | Build, deps, config changes |
| `perf` | Performance improvement |
| `ci` | CI/CD pipeline changes |

**Examples:**
```
feat(auth-service): add forgot-password endpoint
fix(api-gateway): correct JWT whitelist path for /api/auth/refresh
docs: add CONTRIBUTING.md
test(auth-service): add integration tests for token rotation
chore(deps): upgrade spring-boot to 3.2.5
```

---

## PR Checklist Before Merge

- [ ] Branch is up to date with `develop`
- [ ] `mvn clean package` succeeds (no compilation errors)
- [ ] `mvn test` passes (no failing tests)
- [ ] New endpoints documented in API.md
- [ ] New service added to ARCHITECTURE.md services table
- [ ] New env vars added to `.env.example`
- [ ] Flyway migration is additive (no destructive changes)
- [ ] No hardcoded secrets or passwords
- [ ] No `System.out.println` or debug logging left in
- [ ] GlobalExceptionHandler present in new service
- [ ] `/actuator/health` endpoint works

---

## Writing Unit Tests

Use JUnit 5 + Mockito. Place tests in `src/test/java/...` mirroring main package structure.

```java
@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {

    @Mock
    private UserProfileRepository repository;

    @InjectMocks
    private UserProfileServiceImpl service;

    @Test
    void getProfile_returnsProfile_whenExists() {
        // Arrange
        String userId = "user-123";
        UserProfile profile = new UserProfile();
        profile.setId(userId);
        when(repository.findById(userId)).thenReturn(Optional.of(profile));

        // Act
        UserProfileResponse result = service.getProfile(userId);

        // Assert
        assertThat(result.getId()).isEqualTo(userId);
        verify(repository).findById(userId);
    }

    @Test
    void getProfile_throwsNotFound_whenMissing() {
        when(repository.findById(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getProfile("missing"))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
```

**Rules:**
- One test class per service/controller class
- Test method names: `methodName_expectedBehavior_whenCondition`
- Mock all external dependencies (repo, Feign clients, RabbitMQ)
- Aim for 80%+ line coverage

---

## Writing Integration Tests

Use Testcontainers for real database tests.

```java
@SpringBootTest
@Testcontainers
@AutoConfigureMockMvc
class UserProfileControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void getProfile_returns200_whenAuthenticated() throws Exception {
        mockMvc.perform(get("/api/users/profile")
                .header("X-User-Id", "test-user-id"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
    }
}
```

**Testcontainers dependency** (add to service pom.xml for services that need it):
```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <scope>test</scope>
</dependency>
```
