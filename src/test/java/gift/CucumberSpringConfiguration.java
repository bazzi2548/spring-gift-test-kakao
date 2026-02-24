package gift;

import io.cucumber.java.Before;
import io.cucumber.spring.CucumberContextConfiguration;
import io.restassured.RestAssured;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@CucumberContextConfiguration
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class CucumberSpringConfiguration {
	@Value("${test.port:0}")
	int testPort;

	@LocalServerPort
	int port;

	@Autowired
	DatabaseCleaner databaseCleaner;

	@Before
	public void setUp() {
		RestAssured.port = testPort > 0 ? testPort : port;
		databaseCleaner.clear();
	}
}
