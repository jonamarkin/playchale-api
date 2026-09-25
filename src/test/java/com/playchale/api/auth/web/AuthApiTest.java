package com.playchale.api.auth.web;

import java.util.Set;

import com.playchale.api.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Sign-in over HTTP, with the demo code the web app's end-to-end tests type. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthApiTest {

	/** The web app's User type (webapp/app/types/domain.ts). If this changes, both must. */
	private static final Set<String> USER_FIELDS = Set.of("id", "phone", "name", "handle", "avatar", "tint", "area",
			"sports", "position", "createdAt", "onboarded", "payoutPhone");

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ObjectMapper json;

	@BeforeEach
	void emptyTables() {
		jdbc.sql("TRUNCATE users, sign_in_codes, sessions CASCADE").update();
	}

	@Test
	void signInFlow() throws Exception {
		// Signed out: the answer is JSON null, not an empty body or an error.
		mvc.perform(get("/auth/session"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(content().string("null"));

		mvc.perform(post("/auth/codes").contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"024 455 5123\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.demoCode").value("123456"));

		mvc.perform(post("/auth/sessions").contentType(MediaType.APPLICATION_JSON)
			.content("{\"phone\":\"024 455 5123\",\"code\":\"999999\"}"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.error.message").value("That code isn’t right. Check the SMS and try again."));

		var signIn = mvc.perform(post("/auth/sessions").contentType(MediaType.APPLICATION_JSON)
			.content("{\"phone\":\"024 455 5123\",\"code\":\"123456\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.phone").value("+233244555123"))
			.andExpect(jsonPath("$.onboarded").value(false))
			.andReturn()
			.getResponse();

		// Exactly the web app's fields: nothing it doesn't know about, nothing required missing.
		var user = json.readTree(signIn.getContentAsString());
		assertThat(USER_FIELDS).containsAll(user.propertyNames());
		assertThat(user.propertyNames()).contains("id", "phone", "name", "handle", "tint", "sports", "createdAt", "onboarded");

		var setCookie = signIn.getHeader("Set-Cookie");
		assertThat(setCookie).contains("playchale_session=").contains("HttpOnly").contains("SameSite=Lax");
		var session = new Cookie("playchale_session", setCookie.split(";")[0].split("=", 2)[1]);

		mvc.perform(get("/auth/session").cookie(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(user.get("id").asString()));

		mvc.perform(delete("/auth/session").cookie(session)).andExpect(status().isNoContent());
		mvc.perform(get("/auth/session").cookie(session)).andExpect(content().string("null"));
	}

}
