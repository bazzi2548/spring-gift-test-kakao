# 학습 정리

## 1. PostgreSQL + Docker Compose 통합

### 1.1 Docker Compose로 PostgreSQL을 사용하는 이유

#### H2만으로 부족한 경우

| 상황 | H2 결과 | PostgreSQL 결과 |
|:---|:---|:---|
| `TRUNCATE ... CASCADE` | 지원 안 함 | 정상 동작 |
| `SERIAL` 타입 | 지원 안 함 | 정상 동작 |
| 문자열 비교 (대소문자) | 대소문자 구분 | locale에 따라 다름 |
| JSON 컬럼 연산 | 제한적 | `jsonb` 연산자 지원 |
| 날짜/시간 함수 | H2 전용 함수 | PostgreSQL 전용 함수 |

프로덕션이 PostgreSQL이라면, H2에서 통과한 테스트가 PostgreSQL에서 실패할 수 있다. Docker Compose로 PostgreSQL을 로컬에서 실행하면 이 격차를 해소할 수 있다.

#### Docker Compose의 장점
- **일회용 환경**: `docker compose up`으로 생성, `docker compose down`으로 완전 삭제. 로컬 머신에 PostgreSQL을 설치할 필요 없다.
- **팀 공유**: `docker-compose.yml` 파일 하나로 모든 팀원이 동일한 환경을 재현할 수 있다.
- **CI/CD 호환**: GitHub Actions, Jenkins 등에서도 동일한 `docker-compose.yml`을 사용하여 테스트 환경을 구성할 수 있다.

### 1.2 H2와 PostgreSQL의 이중 DB 지원

테스트 환경에서 H2와 PostgreSQL을 모두 지원하기 위해 `DatabaseCleaner`가 DB 종류를 자동 감지한다.

```java
private boolean isPostgres() {
    if (isPostgres == null) {
        try (var connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            isPostgres = metaData.getDatabaseProductName()
                            .toLowerCase().contains("postgresql");
        } catch (Exception e) {
            isPostgres = false;
        }
    }
    return isPostgres;
}
```

#### 코드 상세
- `dataSource.getConnection()`: JDBC `DataSource`에서 커넥션을 획득한다. try-with-resources로 자동 반환한다.
- `connection.getMetaData()`: JDBC 표준 API로 DB 제품명, 버전, 지원 기능 등의 메타 정보를 조회한다.
- `getDatabaseProductName()`: DB 벤더 이름을 반환한다 (예: `"PostgreSQL"`, `"H2"`).
- 결과를 `isPostgres` 필드에 캐싱하여, 매 시나리오마다 DB 연결을 열지 않도록 한다.

### 1.3 DB별 TRUNCATE 전략의 차이

H2와 PostgreSQL은 TRUNCATE 문법이 다르다.

#### PostgreSQL
```java
private void clearPostgres() {
    List<String> tableNames = getTableNames();
    String joined = String.join(", ", tableNames);
    entityManager.createNativeQuery(
        "TRUNCATE TABLE " + joined + " RESTART IDENTITY CASCADE"
    ).executeUpdate();
}
```

- `TRUNCATE TABLE t1, t2, t3`: PostgreSQL은 여러 테이블을 **한 번에** TRUNCATE할 수 있다.
- `RESTART IDENTITY`: auto-increment(SERIAL/IDENTITY) 시퀀스를 1로 리셋한다.
- `CASCADE`: 외래 키로 참조하는 다른 테이블도 함께 TRUNCATE한다. 외래 키 제약 조건이 있어도 에러 없이 실행된다.

#### H2
```java
private void clearH2() {
    entityManager.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate();
    for (final String tableName : getTableNames()) {
        entityManager.createNativeQuery("TRUNCATE TABLE " + tableName).executeUpdate();
        entityManager.createNativeQuery(
            "ALTER TABLE " + tableName + " ALTER COLUMN ID RESTART WITH 1"
        ).executeUpdate();
    }
    entityManager.createNativeQuery("SET REFERENTIAL_INTEGRITY TRUE").executeUpdate();
}
```

- `SET REFERENTIAL_INTEGRITY FALSE`: H2 전용 명령. 외래 키 검사를 일시 비활성화한다 (PostgreSQL의 `CASCADE`에 해당하는 기능).
- 테이블별로 개별 TRUNCATE: H2는 여러 테이블을 한 번에 TRUNCATE하는 문법을 지원하지 않는다.
- `ALTER COLUMN ID RESTART WITH 1`: H2의 auto-increment를 리셋하는 방법 (PostgreSQL의 `RESTART IDENTITY`에 해당).
- `SET REFERENTIAL_INTEGRITY TRUE`: TRUNCATE 완료 후 외래 키 검사를 다시 활성화한다.

### 1.4 JPA 메타모델을 이용한 테이블명 자동 수집

```java
private List<String> getTableNames() {
    return entityManager.getMetamodel().getEntities().stream()
        .map(this::getTableName)
        .toList();
}

private String getTableName(EntityType<?> entity) {
    Class<?> javaType = entity.getJavaType();
    Table table = javaType.getAnnotation(Table.class);
    if (table != null && !table.name().isEmpty()) {
        return table.name();       // @Table(name = "xxx") 우선
    }
    Entity entityAnnotation = javaType.getAnnotation(Entity.class);
    if (entityAnnotation != null && !entityAnnotation.name().isEmpty()) {
        return entityAnnotation.name();  // @Entity(name = "xxx")
    }
    return entity.getName();       // 클래스 이름 기본값
}
```

#### 코드 상세
- `entityManager.getMetamodel()`: JPA 메타모델에 접근한다. 등록된 모든 엔티티 정보를 조회할 수 있다.
- `.getEntities()`: `@Entity`로 등록된 모든 엔티티의 `EntityType` 집합을 반환한다.
- 테이블명 결정 우선순위:
  1. `@Table(name = "xxx")` — 명시적 테이블명
  2. `@Entity(name = "xxx")` — 명시적 엔티티명
  3. 클래스 이름 — 기본값 (예: `Product` → `product`)
- 이 방식을 사용하면 엔티티가 추가/삭제되어도 `DatabaseCleaner`를 수정할 필요가 없다.

### 1.5 Cucumber + JUnit Platform Suite 구조

```java
@Suite
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "gift")
public class CucumberTest {
}
```

#### 어노테이션 상세

- `@Suite`: JUnit Platform Suite API의 진입점 어노테이션. 여러 테스트 엔진을 통합 실행한다. 여기서는 Cucumber 엔진을 JUnit Platform 위에서 실행하는 역할이다.
- `@SelectClasspathResource("features")`: 클래스패스에서 `features` 디렉토리를 탐색한다. 이 디렉토리 아래의 `.feature` 파일들이 시나리오 소스가 된다. 실제 경로는 `src/test/resources/features/`이다.
- `@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "gift")`:
  - `GLUE_PROPERTY_NAME`은 `"cucumber.glue"` 상수이다.
  - `"gift"` 패키지에서 Step Definition 클래스(`@Given`, `@When`, `@Then`)와 `@CucumberContextConfiguration` 클래스를 탐색한다.
  - 이 설정이 없으면 Cucumber가 Step Definition을 찾지 못한다.

---

## 2. Docker Multi-stage Build

### 개념

Docker 이미지를 빌드할 때 **여러 단계(stage)** 를 나누어 진행하는 기법이다. 각 단계는 독립적인 베이스 이미지를 가지며, 최종 이미지에는 마지막 단계의 결과물만 포함된다.

### 왜 필요한가

단일 단계로 빌드하면 Gradle, JDK(컴파일러), 소스 코드, 빌드 캐시 등이 모두 최종 이미지에 남는다. 프로덕션에서 실행할 때는 **컴파일된 JAR 파일과 JRE만** 있으면 충분하다.

```
단일 단계: gradle + JDK + 소스 + 빌드캐시 + JAR = ~800MB
멀티 단계: JRE + JAR = ~200MB
```

### 이번 프로젝트의 Dockerfile 분석

```dockerfile
# ─── 1단계: 빌드 ───
FROM gradle:8-jdk21 AS build    # Gradle + JDK 21이 포함된 이미지
WORKDIR /app
COPY . .                         # 소스 코드 전체 복사
RUN gradle bootJar --no-daemon   # JAR 파일 생성 → build/libs/*.jar

# ─── 2단계: 실행 ───
FROM eclipse-temurin:21-jre      # JRE만 포함된 경량 이미지
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar   # 1단계에서 JAR만 가져옴
ENTRYPOINT ["java", "-jar", "app.jar"]
```

| 단계 | 베이스 이미지 | 역할 | 최종 이미지 포함 여부 |
|:---|:---|:---|:---|
| build | `gradle:8-jdk21` | 소스 컴파일, JAR 생성 | X (버려짐) |
| 실행 | `eclipse-temurin:21-jre` | JAR 실행 | O (최종 이미지) |

### Dockerfile 명령어 상세

#### `FROM <이미지> AS <이름>`
```dockerfile
FROM gradle:8-jdk21 AS build
```
- `FROM`: 베이스 이미지를 지정한다. 모든 Dockerfile은 `FROM`으로 시작해야 한다.
- `AS build`: 이 단계에 `build`라는 별칭을 부여한다. 다음 단계에서 `COPY --from=build`로 참조할 수 있다.
- `gradle:8-jdk21`: Docker Hub의 공식 Gradle 이미지. `8`은 Gradle 메이저 버전, `jdk21`은 내장 JDK 버전이다.

#### `WORKDIR <경로>`
```dockerfile
WORKDIR /app
```
- 컨테이너 내부의 작업 디렉토리를 설정한다. 이후 `COPY`, `RUN` 등의 명령이 이 경로 기준으로 실행된다.
- 디렉토리가 없으면 자동 생성된다.
- 호스트의 디렉토리와 무관하다 — 컨테이너 내부 파일시스템의 경로이다.

#### `COPY <소스> <대상>`
```dockerfile
COPY . .
```
- 첫 번째 `.`: 호스트의 빌드 컨텍스트(프로젝트 루트, `.dockerignore` 제외 항목 반영됨)
- 두 번째 `.`: 컨테이너의 `WORKDIR`(`/app`)
- 즉, 호스트의 프로젝트 파일을 컨테이너의 `/app`으로 복사한다.

```dockerfile
COPY --from=build /app/build/libs/*.jar app.jar
```
- `--from=build`: 현재 단계가 아닌 `build` 단계의 파일시스템에서 복사한다.
- `/app/build/libs/*.jar`: 1단계에서 Gradle이 생성한 Spring Boot JAR 파일 경로
- `app.jar`: 현재 단계 `WORKDIR`(`/app`) 기준 상대 경로. 최종 경로는 `/app/app.jar`이 된다.

#### `RUN <명령>`
```dockerfile
RUN gradle bootJar --no-daemon
```
- 이미지 빌드 시점에 컨테이너 내부에서 명령을 실행한다.
- `bootJar`: Spring Boot 플러그인이 제공하는 Gradle 태스크. 실행 가능한 fat JAR을 생성한다.
- `--no-daemon`: Gradle 데몬을 사용하지 않는다. 컨테이너 빌드는 일회성이므로 데몬(백그라운드 프로세스)을 띄울 이유가 없다. 데몬을 띄우면 메모리만 낭비된다.

#### `ENTRYPOINT` vs `CMD`
```dockerfile
ENTRYPOINT ["java", "-jar", "app.jar"]
```
- `ENTRYPOINT`: 컨테이너가 시작될 때 **항상** 실행되는 명령. `docker run` 시 인자를 추가하면 `ENTRYPOINT` 뒤에 붙는다.
- `CMD`: 기본 명령. `docker run` 시 인자를 주면 **완전히 대체**된다.
- JSON 배열 형식(`["java", "-jar", "app.jar"]`)은 exec form이라 불리며, 셸을 거치지 않고 프로세스를 직접 실행한다. PID 1을 Java 프로세스가 직접 가져가므로 시그널(SIGTERM 등)을 올바르게 받을 수 있다.

```
# exec form (권장) — java가 PID 1
ENTRYPOINT ["java", "-jar", "app.jar"]

# shell form — /bin/sh가 PID 1, java는 자식 프로세스
ENTRYPOINT java -jar app.jar
```

### .dockerignore의 역할

`docker build` 명령을 실행하면 현재 디렉토리 전체를 **빌드 컨텍스트**로 Docker 데몬에 전송한다. `.dockerignore`에 지정된 경로는 전송에서 제외된다.

```
.gradle    # Gradle 캐시 (수백 MB)
build      # 로컬 빌드 산출물 (컨테이너 내부에서 새로 빌드하므로 불필요)
.idea      # IDE 설정
*.iml      # IDE 모듈 파일
.git       # Git 히스토리
```

제외하지 않으면 빌드 컨텍스트 전송에 수십 초가 소요될 수 있다.

---

## 3. Docker Compose 서비스 간 네트워킹

### 기본 원리

Docker Compose는 파일에 정의된 모든 서비스를 **같은 네트워크**에 배치한다. 이 네트워크 안에서 각 서비스는 **서비스 이름을 호스트명으로** 사용하여 서로 통신한다.

```
┌─────────────── Docker Network ───────────────┐
│                                              │
│  ┌──────────┐          ┌──────────┐          │
│  │   app    │ ──5432──▶│ postgres │          │
│  │ (8080)   │          │ (5432)   │          │
│  └──────────┘          └──────────┘          │
│       │                      │               │
└───────┼──────────────────────┼───────────────┘
        │ 28080                │ 15432
   ─────┼──────────────────────┼─────── Host
```

### 접속 경로의 차이

| 접속 주체 | PostgreSQL 주소 | 앱 주소 |
|:---|:---|:---|
| Docker 네트워크 내부 (app → postgres) | `postgres:5432` | - |
| 호스트 머신 (개발자, 테스트 프로세스) | `localhost:15432` | `localhost:28080` |

`docker-compose.yml`에서 이 차이가 드러난다.

```yaml
# postgres 서비스 — 호스트와의 포트 매핑
ports:
  - "15432:5432"    # 호스트 15432 → 컨테이너 5432

# app 서비스 — Docker 네트워크 내부 주소 사용
environment:
  SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/gift_test
  #                                       ^^^^^^^^ 서비스 이름 = 호스트명
```

### docker-compose.yml 주요 옵션 상세

#### `image`
```yaml
app:
  image: gift-app
```
- 컨테이너를 생성할 때 사용할 Docker 이미지 이름이다.
- `build` 속성 대신 `image`를 사용하면, 미리 빌드된 이미지를 참조한다 (이 프로젝트에서는 `./gradlew dockerBuild`로 사전 빌드).
- `build`와 `image`를 동시에 쓰면 빌드한 결과에 해당 이미지 이름을 태깅한다.

#### `ports`
```yaml
ports:
  - "28080:8080"    # "<호스트포트>:<컨테이너포트>"
```
- 호스트의 28080 포트를 컨테이너의 8080 포트로 포워딩한다.
- 호스트에서 `curl localhost:28080` → 컨테이너의 8080으로 전달된다.
- 28080을 사용하는 이유: 호스트에서 이미 8080을 사용 중일 수 있으므로 충돌을 피한다.

#### `environment`
```yaml
environment:
  SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/gift_test
  SPRING_DATASOURCE_USERNAME: gift
  SPRING_DATASOURCE_PASSWORD: gift
  SPRING_JPA_HIBERNATE_DDL_AUTO: create-drop
  SPRING_JPA_DATABASE_PLATFORM: org.hibernate.dialect.PostgreSQLDialect
```
- 컨테이너 내부에 환경변수를 설정한다.
- Spring Boot의 Relaxed Binding에 의해 `SPRING_DATASOURCE_URL` → `spring.datasource.url`로 매핑된다.
- `application.properties`보다 환경변수가 **우선순위가 높으므로**, 컨테이너 내부의 설정 파일에 어떤 값이 있든 여기서 지정한 값이 사용된다.

#### `depends_on`
```yaml
depends_on:
  postgres:
    condition: service_healthy
```
- `service_started` (기본값): 컨테이너 프로세스가 시작되면 즉시 다음 서비스 시작. DB가 준비되지 않은 상태에서 앱이 시작될 수 있다.
- `service_healthy`: 대상 서비스의 healthcheck가 healthy를 반환할 때까지 대기. PostgreSQL이 실제로 쿼리를 받을 수 있는 상태가 된 후에 앱을 시작한다.
- `service_completed_successfully`: 대상 서비스가 정상 종료(exit 0)할 때까지 대기. DB 마이그레이션 컨테이너 같은 일회성 태스크에 사용한다.

---

## 4. 테스트 환경 이중화 전략

### 두 가지 테스트 경로

```
./gradlew test          → H2 인메모리 DB + 임베디드 서버 (빠른 피드백)
./gradlew cucumberTest  → PostgreSQL + Docker 앱 (프로덕션 동일 환경)
```

### 왜 두 개를 유지하는가

| 항목 | `test` (H2) | `cucumberTest` (Docker) |
|:---|:---|:---|
| 실행 속도 | 수 초 | 수십 초 ~ 수 분 |
| DB 호환성 | H2 고유 문법 가능 | PostgreSQL 실제 동작 검증 |
| 인프라 의존 | 없음 | Docker 필요 |
| 용도 | 개발 중 빠른 피드백 | CI/CD, 릴리스 전 검증 |

H2 테스트는 로직의 정합성을 빠르게 확인하고, Docker 테스트는 실제 환경에서의 동작을 보장한다. 두 테스트가 **같은 테스트 코드**를 공유하되, 설정만 달라지는 구조가 핵심이다.

### 동일 코드, 다른 환경

```java
@Value("${test.port:0}")
int testPort;

@LocalServerPort
int port;

@Before
public void setUp() {
    RestAssured.port = testPort > 0 ? testPort : port;
    //                 ^^^^^^^^^^^^^^   ^^^^^^
    //                 cucumberTest     test (H2)
}
```

- `test.port` 시스템 프로퍼티가 **설정되면** → Docker 앱(28080)으로 요청
- **설정되지 않으면** → 임베디드 서버(`@LocalServerPort`)로 요청

### 각 어노테이션/코드 상세

#### `@Value("${test.port:0}")`
```java
@Value("${test.port:0}")
int testPort;
```
- `@Value`: Spring의 프로퍼티 주입 어노테이션. 필드, 생성자 파라미터, 메서드 파라미터에 사용 가능하다.
- `${test.port}`: `test.port`라는 이름의 프로퍼티를 찾는다. 시스템 프로퍼티, 환경변수, properties 파일 순으로 탐색한다.
- `:0`: 기본값(default value). 프로퍼티를 어디에서도 찾지 못하면 `0`을 사용한다. 기본값을 지정하지 않으면(`${test.port}`) 프로퍼티가 없을 때 `IllegalArgumentException`이 발생한다.

#### `@LocalServerPort`
```java
@LocalServerPort
int port;
```
- `@SpringBootTest(RANDOM_PORT)`로 시작된 임베디드 서버의 실제 포트를 주입받는다.
- 내부적으로 `@Value("${local.server.port}")`와 동일하다.
- 테스트가 실행될 때마다 다른 포트가 할당되므로, 여러 테스트를 동시에 실행해도 포트 충돌이 없다.

#### `@Before` (Cucumber)
```java
@Before
public void setUp() { ... }
```
- **Cucumber의 `@Before`** 이다 (`io.cucumber.java.Before`). JUnit의 `@BeforeEach`와 다르다.
- 각 **시나리오(Scenario)** 실행 전에 호출된다. Feature 파일의 시나리오가 3개면 3번 실행된다.
- 순서 지정: `@Before(order = 1)` 형태로 여러 `@Before` 메서드의 실행 순서를 제어할 수 있다 (기본값: 10000).

---

## 5. Spring의 외부 설정 (Externalized Configuration)

### 설정 주입 경로

Spring Boot는 다양한 소스에서 설정값을 읽으며, **우선순위**가 존재한다.

```
우선순위 (높은 순)
1. 커맨드라인 인자        --test.port=28080
2. 시스템 프로퍼티         -Dtest.port=28080          ← Gradle systemProperty
3. 환경변수               SPRING_DATASOURCE_URL=...   ← Docker 환경변수
4. application-{profile}.properties                    ← 프로파일별 설정
5. application.properties                              ← 기본 설정
6. 기본값                  @Value("${test.port:0}")의 :0
```

### 이번 프로젝트에서 사용된 방식들

#### (1) Gradle `systemProperty` → `@Value`

```groovy
// build.gradle
systemProperty 'test.port', '28080'
systemProperty 'spring.profiles.active', 'test'
```

- `systemProperty`: Gradle `Test` 태스크가 fork하는 JVM에 `-D` 옵션을 전달한다.
  - `systemProperty 'test.port', '28080'` → JVM에 `-Dtest.port=28080` 전달
  - `systemProperty 'spring.profiles.active', 'test'` → Spring이 `application-test.properties`를 로드

```java
// CucumberSpringConfiguration.java
@Value("${test.port:0}")    // 시스템 프로퍼티 → Spring이 주입
int testPort;                // 결과: 28080
```

`:0`은 기본값이다. `test.port`가 어디에도 설정되지 않으면 0이 주입된다.

#### (2) Spring Profiles (`spring.profiles.active`)

```groovy
systemProperty 'spring.profiles.active', 'test'
```

이 설정으로 Spring Boot가 `application-test.properties`를 **추가로** 로드한다.

```properties
# src/test/resources/application-test.properties
spring.datasource.url=jdbc:postgresql://localhost:15432/gift_test
spring.datasource.username=gift
spring.datasource.password=gift
spring.jpa.hibernate.ddl-auto=create-drop
spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect
```

프로파일 설정 파일의 값은 기본 `application.properties`보다 우선한다. 따라서 기본 설정이 H2를 사용하더라도, `test` 프로파일이 활성화되면 PostgreSQL로 전환된다.

#### (3) Docker 환경변수 → Spring 설정 오버라이드

```yaml
# docker-compose.yml
environment:
  SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/gift_test
```

Spring Boot는 환경변수 이름을 자동 변환한다.

```
SPRING_DATASOURCE_URL → spring.datasource.url
SPRING_JPA_HIBERNATE_DDL_AUTO → spring.jpa.hibernate.ddl-auto
```

규칙: 대문자 → 소문자, `_` → `.` (Relaxed Binding)

이를 통해 **같은 JAR 파일**이 `application.properties` 변경 없이 다른 DB에 연결할 수 있다.

#### (4) `application-test.properties` 각 설정 항목

```properties
# JDBC 접속 URL
spring.datasource.url=jdbc:postgresql://localhost:15432/gift_test
#                      ^^^^            ^^^^^^^^^  ^^^^^  ^^^^^^^^^
#                      드라이버         호스트     포트    DB명

# DB 접속 계정
spring.datasource.username=gift
spring.datasource.password=gift

# DDL 자동 생성 전략
spring.jpa.hibernate.ddl-auto=create-drop
```

`ddl-auto` 옵션별 동작:

| 값 | 동작 | 용도 |
|:---|:---|:---|
| `none` | 아무것도 안 함 | 프로덕션 (Flyway/Liquibase 사용) |
| `validate` | 엔티티와 테이블 구조 비교만 | 프로덕션 (검증) |
| `update` | 변경분만 적용 (컬럼 추가 등) | 개발 중 |
| `create` | 시작 시 DROP 후 CREATE | 테스트 |
| `create-drop` | 시작 시 CREATE, 종료 시 DROP | 테스트 (깔끔한 정리) |

```properties
# Hibernate가 SQL을 생성할 때 사용하는 방언(Dialect)
spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect
```

PostgreSQL 전용 SQL 문법(SERIAL, TEXT 타입 등)을 사용하도록 지정한다. 미지정 시 Hibernate가 JDBC URL을 보고 자동 감지하지만, 명시적으로 지정하면 감지 실패를 방지할 수 있다.

---

## 6. Gradle 커스텀 태스크와 태스크 의존성

### 태스크 라이프사이클

Gradle 태스크는 **설정(configuration)** 단계와 **실행(execution)** 단계가 분리되어 있다.

```groovy
tasks.register('cucumberTest', Test) {
    // ─── 설정 단계 (항상 실행) ───
    systemProperty 'test.port', '28080'
    dependsOn 'dockerBuild'
    finalizedBy 'dockerDown'

    // ─── 실행 단계 (태스크가 실제로 실행될 때만) ───
    doFirst {
        exec { commandLine 'docker', 'compose', 'up', '-d', '--wait' }
    }
    // doLast { ... }
}
```

### `tasks.register` vs `tasks.create`

```groovy
tasks.register('dockerBuild') { ... }   // lazy — 실제 실행될 때만 설정
tasks.create('dockerBuild') { ... }     // eager — 항상 설정 (비권장)
```

- `register`: Gradle 4.9+에서 도입된 **지연 등록 방식**. 태스크가 실제로 필요할 때만 클로저가 실행된다. `./gradlew test`를 실행했을 때 `dockerBuild` 태스크 설정은 실행되지 않는다.
- `create`: 즉시 생성 방식. 모든 태스크가 항상 설정되므로 빌드 시간이 늘어난다.

### `tasks.register('cucumberTest', Test)` — 두 번째 인자

```groovy
tasks.register('cucumberTest', Test) { ... }
//                              ^^^^
//                              태스크 타입
```

- `Test`는 Gradle의 내장 태스크 타입이다. JVM 테스트 실행에 필요한 기능(classpath 설정, JVM fork, 결과 리포트 등)을 제공한다.
- `Test` 타입이기 때문에 `useJUnitPlatform()`, `systemProperty()` 같은 메서드를 사용할 수 있다.
- 타입을 생략하면 `DefaultTask`가 되며, 기본적으로 아무 동작도 하지 않는 빈 태스크다.

### 태스크 설정 메서드 상세

#### `useJUnitPlatform()`
```groovy
useJUnitPlatform()
```
- JUnit 5 (JUnit Platform) 기반으로 테스트를 실행한다.
- 이 설정이 없으면 Gradle은 JUnit 4 방식으로 테스트를 탐색한다.
- Cucumber의 `@Suite` 어노테이션은 JUnit Platform 위에서 동작하므로 필수다.

#### `systemProperty`
```groovy
systemProperty 'spring.profiles.active', 'test'
systemProperty 'test.port', '28080'
```
- Gradle `Test` 태스크가 fork하는 **자식 JVM**에 시스템 프로퍼티를 전달한다.
- Gradle 자체 JVM이 아니라 테스트를 실행하는 별도 JVM에 `-D` 옵션으로 설정된다.
- Spring이 이 시스템 프로퍼티를 `@Value`나 `Environment`를 통해 읽는다.

#### `description`과 `group`
```groovy
description = 'Runs Cucumber tests against Dockerized app and PostgreSQL'
group = 'verification'
```
- `description`: `./gradlew tasks`로 태스크 목록을 볼 때 표시되는 설명
- `group`: 태스크 목록에서 카테고리를 지정한다. 같은 `group`의 태스크끼리 묶여서 표시된다.

### 태스크 의존성 키워드

| 키워드 | 의미 | 실행 시점 |
|:---|:---|:---|
| `dependsOn` | 이 태스크 전에 실행 | cucumberTest 전에 dockerBuild 실행 |
| `doFirst` | 태스크 본체 직전에 실행 | 테스트 시작 직전 docker compose up |
| `finalizedBy` | 성공/실패 관계없이 후에 실행 | 테스트 끝나면 항상 dockerDown 실행 |

### 실행 순서

```
./gradlew cucumberTest 실행 시:

1. dockerBuild     (dependsOn)    → docker build -t gift-app .
2. doFirst         (cucumberTest) → docker compose up -d --wait
3. cucumberTest    (본체)         → JUnit + Cucumber 테스트 실행
4. dockerDown      (finalizedBy)  → docker compose down
```

`finalizedBy`는 try-finally와 같다. 테스트가 실패하더라도 컨테이너 정리가 보장된다.

### `doLast` vs `doFirst`

```groovy
tasks.register('dockerBuild') {
    doLast {                          // 태스크 실행의 마지막에
        exec {
            commandLine 'docker', 'build', '-t', 'gift-app', '.'
        }
    }
}
```

`dockerBuild` 같은 단순 태스크는 `doLast`에 실행 로직을 넣는다. `cucumberTest`의 `doFirst`는 **Test 태스크 본체(테스트 실행) 직전에** 컨테이너를 기동하는 용도다.

### `exec` 블록과 `commandLine`

```groovy
exec {
    commandLine 'docker', 'build', '-t', 'gift-app', '.'
}
```

- `exec`: 외부 프로세스를 실행하는 Gradle 메서드. 프로세스가 0이 아닌 종료 코드를 반환하면 빌드가 실패한다.
- `commandLine`: 실행할 명령과 인자를 리스트로 지정한다. 각 요소가 별도 인자로 전달되므로 셸 해석을 거치지 않는다.

```groovy
// commandLine 사용 — 셸 해석 없이 직접 실행 (권장)
commandLine 'docker', 'compose', 'up', '-d', '--wait'

// executable + args 사용 — 동일한 결과
executable 'docker'
args 'compose', 'up', '-d', '--wait'
```

#### `docker build` 옵션
```
docker build -t gift-app .
              ^^          ^
              태그명       빌드 컨텍스트 경로
```
- `-t gift-app`: 빌드된 이미지에 `gift-app`이라는 이름(태그)을 부여한다. 이 이름으로 `docker-compose.yml`의 `image: gift-app`이 참조한다.
- `.`: 빌드 컨텍스트. 현재 디렉토리의 파일들을 Docker 데몬에 전송한다 (`.dockerignore` 적용).

#### `docker compose` 옵션
```
docker compose up -d --wait
                  ^^  ^^^^^^
```
- `up`: 서비스를 생성하고 시작한다.
- `-d` (detach): 백그라운드 실행. 이 옵션 없이 실행하면 터미널이 로그 출력에 점유된다.
- `--wait`: 모든 서비스의 healthcheck가 healthy가 될 때까지 명령이 블로킹된다.

```
docker compose down
```
- `down`: 컨테이너, 네트워크를 중지하고 삭제한다.
- 볼륨은 기본적으로 유지된다. `-v` 옵션을 추가하면 볼륨도 삭제한다.

---

## 7. E2E 테스트 아키텍처

### 전체 구조

```
┌─────────────────────── Host ───────────────────────────┐
│                                                        │
│  ┌── Gradle JVM (cucumberTest) ──┐                     │
│  │                               │                     │
│  │  Spring Context (임베디드)      │                     │
│  │  ├─ DatabaseCleaner           │                     │
│  │  │ (EntityManager로 TRUNCATE) │                      │
│  │  └─ Cucumber Steps            │                     │
│  │     └─ RestAssured ──────────────── HTTP ──┐        │
│  │                                │           │        │
│  └────────────────────────────────┘           ▼        │
│                                        ┌───────────┐   │
│           localhost:15432              │Docker App │   │
│                 │                      │  :28080   │   │
│                 ▼                      └─────┬─────┘   │
│  ┌──────── Docker Compose ────────┐         │          │
│  │                                │         │          │
│  │  ┌────────────┐  postgres:5432 │         │          │
│  │  │ PostgreSQL │◀───────────────┼─────────┘          │
│  │  │   :5432    │                │                    │
│  │  └────────────┘                │                    │
│  └────────────────────────────────┘                    │
└────────────────────────────────────────────────────────┘
```

### 핵심: 두 프로세스가 DB를 공유한다

1. **Gradle JVM** (테스트 프로세스): `localhost:15432`로 PostgreSQL에 접속
2. **Docker App** (앱 컨테이너): `postgres:5432`로 같은 PostgreSQL에 접속

같은 DB를 바라보기 때문에:
- `DatabaseCleaner`가 Gradle JVM에서 TRUNCATE하면 → Docker App도 빈 DB를 보게 됨
- Docker App이 데이터를 쓰면 → Gradle JVM의 테스트에서 API 조회로 확인 가능

### 임베디드 Spring 컨텍스트의 역할

`cucumberTest`에서 `@SpringBootTest(RANDOM_PORT)`로 임베디드 서버도 뜬다. 하지만 **HTTP 요청은 Docker 앱으로** 보낸다. 임베디드 컨텍스트가 필요한 이유는 `DatabaseCleaner`에서 사용하는 `EntityManager`를 Spring이 관리해야 하기 때문이다.

```java
@Autowired
DatabaseCleaner databaseCleaner;   // Spring이 EntityManager를 주입

@Before
public void setUp() {
    RestAssured.port = 28080;       // HTTP는 Docker 앱으로
    databaseCleaner.clear();        // DB 정리는 임베디드 컨텍스트의 EntityManager로
}
```

---

## 8. 컨테이너 Healthcheck

### 왜 필요한가

`docker compose up -d`는 컨테이너를 **백그라운드로 시작만** 한다. 컨테이너가 시작되었다고 해서 애플리케이션이 요청을 받을 준비가 된 것은 아니다.

```
컨테이너 시작 ─── JVM 부팅 ─── Spring 초기화 ─── DB 연결 ─── 준비 완료
     ↑                                                        ↑
  docker up                                              요청 수신 가능
  (여기서 바로 테스트하면 실패)                            (여기까지 기다려야 함)
```

### Healthcheck 설정 분석

#### PostgreSQL

```yaml
healthcheck:
  test: ["CMD-SHELL", "pg_isready -U gift -d gift_test"]
  interval: 3s
  timeout: 3s
  retries: 10
```

각 옵션 설명:

| 옵션 | 값 | 의미 |
|:---|:---|:---|
| `test` | `pg_isready -U gift -d gift_test` | 체크 명령. PostgreSQL 내장 도구로 연결 가능 여부를 확인한다 |
| `interval` | `3s` | 체크 간격. 3초마다 healthcheck를 실행한다 |
| `timeout` | `3s` | 체크 타임아웃. 명령이 3초 내 응답하지 않으면 해당 체크를 실패로 간주한다 |
| `retries` | `10` | 재시도 횟수. 10번 연속 실패하면 `unhealthy` 상태가 된다 |

`pg_isready` 옵션:
- `-U gift`: 접속할 사용자명
- `-d gift_test`: 접속할 데이터베이스명
- 성공 시 exit code 0, 실패 시 non-zero를 반환한다

#### `test` 필드의 형식

```yaml
# CMD-SHELL: 셸(/bin/sh)을 통해 명령 실행 — 파이프, &&, || 사용 가능
test: ["CMD-SHELL", "pg_isready -U gift -d gift_test"]

# CMD: 셸 없이 직접 실행 — 단순 명령에 적합
test: ["CMD", "pg_isready", "-U", "gift", "-d", "gift_test"]
```

#### Spring Boot 앱

```yaml
healthcheck:
  test: ["CMD-SHELL", "curl -s -o /dev/null http://localhost:8080 || exit 1"]
  interval: 5s
  timeout: 5s
  retries: 20
  start_period: 30s
```

| 옵션 | 값 | 의미 |
|:---|:---|:---|
| `test` | `curl -s -o /dev/null ... \|\| exit 1` | HTTP 요청으로 앱이 응답하는지 확인한다 |
| `interval` | `5s` | 5초마다 체크. PostgreSQL보다 길게 설정 (앱은 부팅이 더 느림) |
| `timeout` | `5s` | 5초 내 응답 없으면 실패 |
| `retries` | `20` | 20번까지 재시도. Spring Boot 앱은 부팅 시간이 길 수 있으므로 여유있게 설정 |
| `start_period` | `30s` | **유예 기간**. 컨테이너 시작 후 30초 동안은 healthcheck 실패가 retries에 카운트되지 않는다 |

`curl` 옵션:
- `-s` (silent): 진행률 표시를 숨긴다. 로그가 깔끔해진다.
- `-o /dev/null`: 응답 본문을 버린다. healthcheck에는 본문 내용이 불필요하다.
- `|| exit 1`: curl이 실패하면(서버 연결 불가 등) exit 1을 반환하여 healthcheck 실패를 명시한다.

#### `-f` 옵션을 사용하지 않는 이유

처음에는 `curl -f`(HTTP 4xx/5xx 시 실패)를 사용했으나, 이 앱은 루트 경로(`/`)에 매핑된 엔드포인트가 없어 항상 **404**를 반환했다. `-f`는 404도 실패로 처리하므로 서버가 정상 가동 중인데도 healthcheck가 실패했다.

```
curl -f http://localhost:8080   → 404 → exit code 22 (실패) ← 서버는 정상인데 unhealthy
curl -s -o /dev/null http://localhost:8080  → 404 → exit code 0 (성공) ← 서버 응답 확인됨
```

healthcheck의 목적은 **서버가 HTTP 요청을 받을 수 있는 상태인지** 확인하는 것이므로, 응답 코드와 무관하게 TCP 연결 + HTTP 응답이 오면 healthy로 판단하는 것이 적절하다.

`start_period`는 Spring Boot처럼 부팅에 시간이 걸리는 앱에 특히 중요하다.

```
start_period가 없는 경우:
  0s  → check fail (1/20)    Spring 아직 부팅 중
  5s  → check fail (2/20)    Spring 아직 부팅 중
  10s → check fail (3/20)    ...
  ...                         retries를 빠르게 소진 → unhealthy

start_period=30s인 경우:
  0s  → check fail (카운트 안 함)    Spring 아직 부팅 중
  5s  → check fail (카운트 안 함)    Spring 아직 부팅 중
  ...
  30s → check fail (1/20)            이제부터 카운트 시작
  35s → check pass → healthy!        Spring 부팅 완료
```

### `--wait` 플래그

```bash
docker compose up -d --wait
```

`--wait`는 **모든 서비스의 healthcheck가 healthy**가 될 때까지 명령이 블로킹된다. 이 플래그 덕분에 Gradle의 `doFirst`가 완료된 시점에는 앱이 확실히 준비된 상태다.

```
docker compose up -d --wait
  │
  ├── postgres 시작 → healthcheck 대기 → healthy ✓
  │
  ├── app 시작 (postgres healthy 후) → healthcheck 대기 → healthy ✓
  │
  └── 명령 완료 (이 시점에 모든 서비스 준비됨)
      │
      ▼
  cucumberTest 본체 실행 (안전하게 HTTP 요청 가능)
```
