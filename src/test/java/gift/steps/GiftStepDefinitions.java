package gift.steps;

import gift.model.Member;
import gift.model.MemberRepository;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class GiftStepDefinitions {
	@Autowired
	private ScenarioContext context;

	@Autowired
	private MemberRepository memberRepository;

	private final Map<String, Long> memberIds = new HashMap<>();
	private Long productId;
	private final Map<String, Long> optionIds = new HashMap<>();
	private Long giftId;

	@Given("회원 {string}이 등록되어 있다")
	public void 회원이_등록되어_있다(String name) {
		Member member = memberRepository.save(new Member(name, name + "@test.com"));
		memberIds.put(name, member.getId());
	}

	@Given("{string} 상품이 {int}원, 이미지 {string}로 등록되어 있다")
	public void 상품이_등록되어_있다(String name, int price, String imageUrl) {
		productId = RestAssured.given()
			.contentType(ContentType.JSON)
			.body(Map.of("name", name, "price", price, "imageUrl", imageUrl, "categoryId", context.getCategoryId()))
			.when()
			.post("/api/products")
			.then()
			.statusCode(HttpStatus.OK.value())
			.extract()
			.jsonPath().getLong("id");
	}

	@And("{string} 옵션이 수량 {int}으로 등록되어 있다")
	public void 옵션이_등록되어_있다(String name, int quantity) {
		Long optionId = RestAssured.given()
			.contentType(ContentType.JSON)
			.body(Map.of("name", name, "quantity", quantity, "productId", productId))
			.when()
			.post("/api/options")
			.then()
			.statusCode(HttpStatus.OK.value())
			.extract()
			.jsonPath().getLong("id");
		optionIds.put(name, optionId);
	}

	@When("{string}이 {string}에게 {string} 옵션을 {int}개 선물한다 {string}")
	public void 선물한다(String senderName, String receiverName, String optionName, int quantity, String message) {
		Long senderId = memberIds.get(senderName);
		Long receiverId = memberIds.get(receiverName);
		Long optionId = optionIds.get(optionName);

		ExtractableResponse<Response> response = RestAssured.given()
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

		context.setResponse(response);
		if (response.statusCode() == HttpStatus.OK.value()) {
			giftId = response.jsonPath().getLong("id");
		}
	}

	@And("{string} 옵션의 남은 수량은 {int}이다")
	public void 옵션의_남은_수량은(String optionName, int expectedQuantity) {
		Long optionId = optionIds.get(optionName);
		int remainingQuantity = RestAssured.given()
			.when()
			.get("/api/options/{id}", optionId)
			.then()
			.statusCode(HttpStatus.OK.value())
			.extract()
			.jsonPath().getInt("quantity");
		assertThat(remainingQuantity).isEqualTo(expectedQuantity);
	}

	@And("선물의 수신자는 {string}이다")
	public void 선물의_수신자는(String receiverName) {
		ExtractableResponse<Response> detail = RestAssured.given()
			.when()
			.get("/api/gifts/{id}", giftId)
			.then()
			.statusCode(HttpStatus.OK.value())
			.extract();
		assertThat(detail.jsonPath().getLong("receiver.id")).isEqualTo(memberIds.get(receiverName));
	}

	@And("선물의 메시지는 {string}이다")
	public void 선물의_메시지는(String expectedMessage) {
		ExtractableResponse<Response> detail = RestAssured.given()
			.when()
			.get("/api/gifts/{id}", giftId)
			.then()
			.statusCode(HttpStatus.OK.value())
			.extract();
		assertThat(detail.jsonPath().getString("message")).isEqualTo(expectedMessage);
	}
}
