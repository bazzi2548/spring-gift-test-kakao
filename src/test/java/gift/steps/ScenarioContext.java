package gift.steps;

import io.cucumber.spring.ScenarioScope;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import org.springframework.stereotype.Component;

@Component
@ScenarioScope
public class ScenarioContext {
	private Long categoryId;
	private ExtractableResponse<Response> response;

	public Long getCategoryId() {
		return categoryId;
	}

	public void setCategoryId(Long categoryId) {
		this.categoryId = categoryId;
	}

	public ExtractableResponse<Response> getResponse() {
		return response;
	}

	public void setResponse(ExtractableResponse<Response> response) {
		this.response = response;
	}
}
