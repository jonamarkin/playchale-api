package com.playchale.api.web;

import com.playchale.api.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The HTTP behaviour every endpoint shares: health, errors, request IDs, CORS. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, WebLayerTest.Probes.class })
class WebLayerTest {

	@Autowired
	MockMvc mvc;

	@Test
	void healthReportsTheDatabase() throws Exception {
		mvc.perform(get("/healthz"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ok"))
			.andExpect(jsonPath("$.database").value("ok"))
			.andExpect(jsonPath("$.env").value("development"))
			.andExpect(header().string("X-Request-Id", not(emptyOrNullString())));
	}

	@Test
	void unknownPathsGetTheJsonErrorShape() throws Exception {
		mvc.perform(get("/nope"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("not-found"))
			.andExpect(jsonPath("$.error.message").value("There’s nothing here."));
	}

	@Test
	void userFacingErrorsKeepTheirCodeAndMessage() throws Exception {
		mvc.perform(get("/test/conflict"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("conflict"))
			.andExpect(jsonPath("$.error.message").value("Sorry, this game just filled up."));
	}

	@Test
	void unexpectedErrorsAreHidden() throws Exception {
		mvc.perform(get("/test/boom"))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.error.code").value("internal"))
			.andExpect(jsonPath("$.error.message").value("Something went wrong on our side. Please try again."));
	}

	@Test
	void unknownJsonFieldsAreRefused() throws Exception {
		mvc.perform(post("/test/echo").contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"x\",\"phoneNumber\":\"y\"}"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.error.code").value("invalid"));
	}

	@Test
	void theWebAppMayCallFromTheBrowser() throws Exception {
		mvc.perform(options("/healthz").header("Origin", "http://localhost:3000").header("Access-Control-Request-Method", "GET"))
			.andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
			.andExpect(header().string("Access-Control-Allow-Credentials", "true"));
	}

	@Test
	void otherSitesMayNot() throws Exception {
		mvc.perform(options("/healthz").header("Origin", "https://evil.example").header("Access-Control-Request-Method", "GET"))
			.andExpect(header().string("Access-Control-Allow-Origin", nullValue()));
	}

	/** Endpoints that exist only in this test, to exercise the error handling. */
	@TestConfiguration
	static class Probes {

		@RestController
		static class ProbeController {

			record Echo(String phone) {
			}

			@GetMapping("/test/conflict")
			void conflict() {
				throw AppException.conflict("Sorry, this game just filled up.");
			}

			@GetMapping("/test/boom")
			void boom() {
				throw new IllegalStateException("connection refused on 10.0.0.4:5432");
			}

			@PostMapping("/test/echo")
			Echo echo(@RequestBody Echo body) {
				return body;
			}

		}

	}

}
