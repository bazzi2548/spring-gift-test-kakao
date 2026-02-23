package gift.steps;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class CommonStepDefinitions {
	@Autowired
	private ScenarioContext context;

	@Given("{string} 카테고리가 등록되어 있다")
	public void 카테고리가_등록되어_있다(String name) {
		Long categoryId = RestAssured.given()
			.contentType(ContentType.JSON)
			.body(Map.of("name", name))
			.when()
			.post("/api/categories")
			.then()
			.statusCode(HttpStatus.OK.value())
			.extract()
			.jsonPath().getLong("id");
		context.setCategoryId(categoryId);
	}

	@Then("응답 상태 코드는 {int}이다")
	public void 응답_상태_코드는_이다(int statusCode) {
		assertThat(context.getResponse().statusCode()).isEqualTo(statusCode);
	}
}
