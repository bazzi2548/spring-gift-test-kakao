package gift;

import gift.model.Member;
import gift.model.MemberRepository;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GiftAcceptanceTest {
	@LocalServerPort
	int port;

	@Autowired
	DatabaseCleaner databaseCleaner;

	@Autowired
	MemberRepository memberRepository;

	@BeforeEach
	void setUp() {
		RestAssured.port = port;
		databaseCleaner.clear();
	}

	@DisplayName("사용자가 상품을 선택해 자신에게 선물하면, 해당 옵션의 재고 수량이 감소한다")
	@Test
	void 나에게_선물하기_재고_차감() {
		// given
		Member sender = memberRepository.save(new Member("보내는사람", "sender@test.com"));
		Long categoryId = 카테고리를_생성한다("음료");
		Long productId = 상품을_등록한다("아메리카노", 500, "/img/ame", categoryId);
		Long optionId = 옵션을_등록한다("ICE", 10, productId);

		// when
		선물을_보낸다(sender.getId(), optionId, 1, sender.getId(), "나에게 주는 선물");

		// then
		int remainingQuantity = 옵션을_조회한다(optionId).jsonPath().getInt("quantity");
		assertThat(remainingQuantity).isEqualTo(9);
	}

	@DisplayName("옵션 수량이 부족하면 선물 요청이 거부된다")
	@Test
	void 재고_부족_시_선물_불가() {
		// given
		Member sender = memberRepository.save(new Member("보내는사람", "sender@test.com"));
		Long categoryId = 카테고리를_생성한다("음료");
		Long productId = 상품을_등록한다("아메리카노", 500, "/img/ame", categoryId);
		Long optionId = 옵션을_등록한다("ICE", 1, productId);

		// when
		ExtractableResponse<Response> response = 선물을_보낸다(sender.getId(), optionId, 2, sender.getId(), "수량 초과");

		// then
		assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());

		int remainingQuantity = 옵션을_조회한다(optionId).jsonPath().getInt("quantity");
		assertThat(remainingQuantity).isEqualTo(1);
	}

	@DisplayName("선물을 보낼 때 입력한 수신자와 메시지가 정확히 기록된다")
	@Test
	void 선물_발송_데이터_일관성() {
		// given
		Member sender = memberRepository.save(new Member("보내는사람", "sender@test.com"));
		Member receiver = memberRepository.save(new Member("받는사람", "receiver@test.com"));
		Long categoryId = 카테고리를_생성한다("음료");
		Long productId = 상품을_등록한다("라떼", 1000, "/img/latte", categoryId);
		Long optionId = 옵션을_등록한다("HOT", 5, productId);

		// when
		ExtractableResponse<Response> giftResponse = 선물을_보낸다(sender.getId(), optionId, 1, receiver.getId(), "생일 축하해!");
		Long giftId = giftResponse.jsonPath().getLong("id");

		// then
		ExtractableResponse<Response> detail = 선물을_조회한다(giftId);
		assertThat(detail.jsonPath().getLong("receiver.id")).isEqualTo(receiver.getId());
		assertThat(detail.jsonPath().getString("message")).isEqualTo("생일 축하해!");
	}

	private Long 카테고리를_생성한다(String name) {
		return RestAssured.given()
			.contentType(ContentType.JSON)
			.body(Map.of("name", name))
			.when()
			.post("/api/categories")
			.then()
			.statusCode(HttpStatus.OK.value())
			.extract()
			.jsonPath().getLong("id");
	}

	private Long 상품을_등록한다(String name, int price, String imageUrl, Long categoryId) {
		return RestAssured.given()
			.contentType(ContentType.JSON)
			.body(Map.of("name", name, "price", price, "imageUrl", imageUrl, "categoryId", categoryId))
			.when()
			.post("/api/products")
			.then()
			.statusCode(HttpStatus.OK.value())
			.extract()
			.jsonPath().getLong("id");
	}

	private Long 옵션을_등록한다(String name, int quantity, Long productId) {
		return RestAssured.given()
			.contentType(ContentType.JSON)
			.body(Map.of("name", name, "quantity", quantity, "productId", productId))
			.when()
			.post("/api/options")
			.then()
			.statusCode(HttpStatus.OK.value())
			.extract()
			.jsonPath().getLong("id");
	}

	private ExtractableResponse<Response> 선물을_보낸다(Long senderId, Long optionId, int quantity, Long receiverId, String message) {
		return RestAssured.given()
			.contentType(ContentType.JSON)
			.header("Member-Id", senderId)
			.body(Map.of(
				"optionId", optionId,
				"quantity", quantity,
				"receiverId", receiverId,
				"message", message
			))
			.when()
			.post("/api/gifts")
			.then()
			.extract();
	}

	private ExtractableResponse<Response> 옵션을_조회한다(Long optionId) {
		return RestAssured.given()
			.when()
			.get("/api/options/{id}", optionId)
			.then()
			.statusCode(HttpStatus.OK.value())
			.extract();
	}

	private ExtractableResponse<Response> 선물을_조회한다(Long giftId) {
		return RestAssured.given()
			.when()
			.get("/api/gifts/{id}", giftId)
			.then()
			.statusCode(HttpStatus.OK.value())
			.extract();
	}
}
