# spring-gift-test

## 실행 방법

### 요구 사항
- Java 21
- Docker & Docker Compose (PostgreSQL 테스트 시)

### 테스트 실행

#### 기본 테스트 (H2 인메모리 DB)
```bash
./gradlew test
```

#### PostgreSQL + Docker Compose 테스트
```bash
./gradlew cucumberTest
```
Docker Compose로 PostgreSQL 17 컨테이너를 자동 실행한 뒤 Cucumber 테스트를 수행하고, 완료 후 컨테이너를 자동 정리합니다.

### 테스트 구조
```
src/test/
├── java/gift/
│   ├── CucumberTest.java                  # Cucumber 실행 진입점 (JUnit Platform Suite)
│   ├── CucumberSpringConfiguration.java   # Spring Boot 통합 + 시나리오 격리 설정
│   ├── DatabaseCleaner.java               # 매 시나리오 전 DB TRUNCATE
│   └── steps/
│       ├── ScenarioContext.java            # 시나리오 간 공유 상태 (@ScenarioScope)
│       ├── CommonStepDefinitions.java      # 카테고리 등록, 응답 코드 검증
│       ├── ProductStepDefinitions.java     # 상품 등록/조회 Step Definitions
│       └── GiftStepDefinitions.java        # 선물하기 Step Definitions
└── resources/features/
    ├── product.feature                     # 상품 관리 시나리오 (Gherkin)
    └── gift.feature                        # 선물하기 시나리오 (Gherkin)
```

### 주요 기술 스택
| 구분 | 기술 |
| :--- | :--- |
| BDD | Cucumber 7 + Gherkin (한글) |
| 테스트 | JUnit Platform Suite + RestAssured |
| 통합 | cucumber-spring + SpringBootTest (RANDOM_PORT) |
| 데이터 격리 | DatabaseCleaner (TRUNCATE) + H2 인메모리 DB |
| 인프라 | Docker Compose (PostgreSQL 17) |

---

## 1. 비즈니스 맥락 및 검증 범위
본 프로젝트는 '카카오톡 선물하기'의 핵심 도메인인 제품 관리와 선물 발송 로직을 검증한다. 현재 시스템은 **옵션 재고 차감 기반**으로 동작하며, 인증은 **HTTP Header(Member-Id)**를 통해 수행되는 구조를 따른다.

---

## 2. 검증할 행위 목록 (Behavior List)

### [제품 관리 행위]
1. **카테고리 기반 상품 진열**: 관리자는 카테고리를 생성하고 상품을 등록하여 판매를 시작한다.
2. **전체 상품 목록 조회**: 사용자는 시스템에 등록된 모든 상품의 정보(이름, 가격 등)를 확인할 수 있다.
3. **[예외] 존재하지 않는 카테고리에 상품 등록**: 유효하지 않은 카테고리 ID 사용 시 상품 등록을 거부하고 데이터 무결성을 유지한다.

### [선물하기 행위]
4. **나에게 선물하기 (재고 차감)**: 사용자가 상품을 선택해 자신에게 선물하면, 해당 상품 옵션의 **재고 수량이 1 감소**한다.
5. **[예외] 재고 부족 시 선물 불가**: 옵션 수량이 0인 상품을 선물하려고 하면 요청이 거부(IllegalStateException 발생 등)되어야 한다.
6. **선물 발송 데이터 일관성**: 사용자가 선물을 보낼 때 작성한 **수신자 ID와 메시지**가 시스템에 정확히 기록되어야 한다.

---

## 3. 테스트 데이터 전략

- **API 기반 셋업 (Outside-In Setup)**:
    - 테스트에 필요한 사전 조건(카테고리, 상품)은 DB 직접 삽입이 아닌 실제 서비스 API 호출을 통해 준비한다.
- **회원 데이터 준비 (Repository Direct Insert)**:
    - 현재 회원 등록 API가 부재하므로, 테스트 시작 전 `MemberRepository`를 통해 테스트용 회원들을 직접 삽입하고 그 ID를 헤더(`Member-Id`)에 사용한다.
- **테스트 격리 (Isolation)**:
    - `DatabaseCleaner`를 구현하여 매 테스트 메서드 실행 후 모든 테이블을 `TRUNCATE` 처리한다.
    - H2 인메모리 DB를 사용하여 독립적인 환경을 보장한다.

---

## 4. 검증 전략

- **상태 변화 관찰 (State Observation)**:
    - 선물 발송 전후의 **옵션 상세 조회 API**를 호출하여 재고 수량(quantity)의 변화를 비교함으로써 행위의 성공을 검증한다.
- **데이터 일관성 검증**:
    - 발송된 선물 상세 조회 API를 통해 발송 시 입력한 메시지와 수신자 정보가 DB에 정확히 기록되었는지 확인한다.
- **Black-box 테스트**:
    - `RestAssured`를 사용하여 오직 HTTP 요청/응답으로만 시스템을 관찰한다.

---

## 5. 주요 의사결정 및 사전 과제

### [주요 의사결정]
| 항목 | 결정 사항 | 근거 |
| :--- | :--- | :--- |
| **Gift 도메인 영속화** | **POJO → @Entity 변경** | 선물(Gift)은 서비스의 핵심 도메인이며, 상태 추적 및 데이터 일관성 검증을 위해 DB 저장이 필수적임 |
| **검증 지표** | 옵션 재고 및 선물 내역 | 재고 차감은 비즈니스 무결성을, 선물 내역은 데이터 일관성을 증명함 |
| **인증 대체** | Member-Id 헤더 사용 | 현재 코드베이스의 회원 식별 스펙을 존중하면서 다자간 테스트 수행 가능 |

### [사전 구현 필요 사항]
인수 테스트의 검증 가능성을 확보하기 위해 아래 기능을 선제적으로 구현한다.
1. **Gift 엔티티화**: `Gift` 클래스를 JPA 엔티티로 변경하고 저장 로직(`GiftRepository`)을 추가한다.
2. **조회용 API 추가**:
- `GET /api/options/{id}`: 옵션 재고 수량 확인용.
- `GET /api/gifts/{id}`: 선물 발송 내역 확인용.
3. **DatabaseCleaner**: 테스트 격리를 위한 TRUNCATE 유틸리티.