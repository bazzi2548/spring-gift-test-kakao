package gift;

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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductAcceptanceTest {
	@LocalServerPort
	int port;

	@Autowired
	DatabaseCleaner databaseCleaner;

	@BeforeEach
	void setUp() {
		RestAssured.port = port;
		databaseCleaner.clear();
	}

	@DisplayName("관리자는 카테고리를 생성하고 상품을 등록하여 판매를 시작한다")
	@Test
	void 카테고리_기반_상품_진열() {
		// given
		Long categoryId = 카테고리를_생성한다("음료");

		// when
		ExtractableResponse<Response> 아메리카노 = 상품을_등록한다("아메리카노", 500, "/img/ame", categoryId);
		ExtractableResponse<Response> 라떼 = 상품을_등록한다("라떼", 1000, "/img/latte", categoryId);
		ExtractableResponse<Response> 모카 = 상품을_등록한다("모카", 1500, "/img/moca", categoryId);

		// then
		assertThat(아메리카노.statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(라떼.statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(모카.statusCode()).isEqualTo(HttpStatus.OK.value());
	}

	@DisplayName("사용자는 시스템에 등록된 모든 상품의 정보를 확인할 수 있다")
	@Test
	void 전체_상품_목록_조회() {
		// given
		Long categoryId = 카테고리를_생성한다("음료");
		상품을_등록한다("아메리카노", 500, "/img/ame", categoryId);
		상품을_등록한다("라떼", 1000, "/img/latte", categoryId);
		상품을_등록한다("모카", 1500, "/img/moca", categoryId);

		// when
		ExtractableResponse<Response> response = 전체_상품을_조회한다();

		// then
		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
		List<String> productNames = response.jsonPath().getList("name", String.class);
		assertThat(productNames).containsExactlyInAnyOrder("아메리카노", "라떼", "모카");
	}

	@DisplayName("존재하지 않는 카테고리에 상품을 등록하면 요청이 거부된다")
	@Test
	void 유효하지_않은_카테고리_상품_등록() {
		// given
		Long invalidCategoryId = 999L;

		// when
		ExtractableResponse<Response> response = 상품을_등록한다("아메리카노", 500, "/img/ame", invalidCategoryId);

		// then
		assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
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

	private ExtractableResponse<Response> 상품을_등록한다(String name, int price, String imageUrl, Long categoryId) {
		return RestAssured.given()
			.contentType(ContentType.JSON)
			.body(Map.of(
				"name", name,
				"price", price,
				"imageUrl", imageUrl,
				"categoryId", categoryId
			))
			.when()
			.post("/api/products")
			.then()
			.extract();
	}

	private ExtractableResponse<Response> 전체_상품을_조회한다() {
		return RestAssured.given()
			.when()
			.get("/api/products")
			.then()
			.extract();
	}
}
