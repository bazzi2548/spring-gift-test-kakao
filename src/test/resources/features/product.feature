Feature: 상품 관리
  관리자는 카테고리를 생성하고 상품을 등록하여 판매할 수 있다.
  사용자는 시스템에 등록된 상품 정보를 확인할 수 있다.

  Scenario: 카테고리 기반 상품 진열
    Given "음료" 카테고리가 등록되어 있다
    When 다음 상품을 등록한다
      | name     | price | imageUrl   |
      | 아메리카노 | 500   | /img/ame   |
      | 라떼      | 1000  | /img/latte |
      | 모카      | 1500  | /img/moca  |
    Then 모든 상품이 정상 등록된다

  Scenario: 전체 상품 목록 조회
    Given "음료" 카테고리가 등록되어 있다
    And 다음 상품이 등록되어 있다
      | name     | price | imageUrl   |
      | 아메리카노 | 500   | /img/ame   |
      | 라떼      | 1000  | /img/latte |
      | 모카      | 1500  | /img/moca  |
    When 전체 상품 목록을 조회한다
    Then 응답 상태 코드는 200이다
    And 상품 목록에 "아메리카노", "라떼", "모카"가 포함되어 있다

  Scenario: 존재하지 않는 카테고리에 상품 등록 시 거부
    When 존재하지 않는 카테고리로 "아메리카노" 상품을 등록한다
    Then 응답 상태 코드는 404이다
