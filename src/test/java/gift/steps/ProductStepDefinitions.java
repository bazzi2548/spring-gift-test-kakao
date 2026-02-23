package gift.steps;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class ProductStepDefinitions {
	@Autowired
	private ScenarioContext context;

	private final List<ExtractableResponse<Response>> productResponses = new ArrayList<>();

	@When("다음 상품을 등록한다")
	public void 다음_상품을_등록한다(DataTable dataTable) {
		List<Map<String, String>> rows = dataTable.asMaps();
		for (Map<String, String> row : rows) {
			productResponses.add(RestAssured.given()
				.contentType(ContentType.JSON)
				.body(Map.of(
					"name", row.get("name"),
					"price", Integer.parseInt(row.get("price")),
					"imageUrl", row.get("imageUrl"),
					"categoryId", context.getCategoryId()
				))
				.when()
				.post("/api/products")
				.then()
				.extract());
		}
	}

	@Then("모든 상품이 정상 등록된다")
	public void 모든_상품이_정상_등록된다() {
		for (ExtractableResponse<Response> response : productResponses) {
			assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
		}
	}

	@And("다음 상품이 등록되어 있다")
	public void 다음_상품이_등록되어_있다(DataTable dataTable) {
		List<Map<String, String>> rows = dataTable.asMaps();
		for (Map<String, String> row : rows) {
			RestAssured.given()
				.contentType(ContentType.JSON)
				.body(Map.of(
					"name", row.get("name"),
					"price", Integer.parseInt(row.get("price")),
					"imageUrl", row.get("imageUrl"),
					"categoryId", context.getCategoryId()
				))
				.when()
				.post("/api/products")
				.then()
				.statusCode(HttpStatus.OK.value());
		}
	}

	@When("전체 상품 목록을 조회한다")
	public void 전체_상품_목록을_조회한다() {
		context.setResponse(RestAssured.given()
			.when()
			.get("/api/products")
			.then()
			.extract());
	}

	@When("존재하지 않는 카테고리로 {string} 상품을 등록한다")
	public void 존재하지_않는_카테고리로_상품을_등록한다(String name) {
		context.setResponse(RestAssured.given()
			.contentType(ContentType.JSON)
			.body(Map.of(
				"name", name,
				"price", 500,
				"imageUrl", "/img/default",
				"categoryId", 999
			))
			.when()
			.post("/api/products")
			.then()
			.extract());
	}

	@And("상품 목록에 {string}, {string}, {string}가 포함되어 있다")
	public void 상품_목록에_가_포함되어_있다(String name1, String name2, String name3) {
		List<String> productNames = context.getResponse().jsonPath().getList("name", String.class);
		assertThat(productNames).containsExactlyInAnyOrder(name1, name2, name3);
	}
}
