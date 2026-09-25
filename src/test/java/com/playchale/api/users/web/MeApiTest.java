package com.playchale.api.users.web;

import com.playchale.api.TestSignIn;
import com.playchale.api.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Onboarding and editing your own profile over HTTP, as the web app does it. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MeApiTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void emptyTables() {
		jdbc.sql("TRUNCATE users, sign_in_codes, sessions CASCADE").update();
	}

	@Test
	void signedOutPlayersCantChangeAnything() throws Exception {
		mvc.perform(patch("/me").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Kwame\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("unauthenticated"))
			.andExpect(jsonPath("$.error.message").value("Please sign in to continue."));
	}

	@Test
	void onboardingThenEditing() throws Exception {
		var kwame = TestSignIn.as(mvc, "024 455 5123");

		mvc.perform(post("/me/onboarding").cookie(kwame).contentType(MediaType.APPLICATION_JSON).content("""
				{"name":"Kwame Mensah","handle":"Kwame","sports":["football"],"area":"East Legon"}
				"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.onboarded").value(true))
			.andExpect(jsonPath("$.handle").value("kwame"))
			.andExpect(jsonPath("$.sports[0]").value("football"))
			.andExpect(jsonPath("$.area").value("East Legon"));

		mvc.perform(get("/auth/session").cookie(kwame)).andExpect(jsonPath("$.onboarded").value(true));

		mvc.perform(patch("/me").cookie(kwame).contentType(MediaType.APPLICATION_JSON)
			.content("{\"position\":\"Striker\",\"payoutPhone\":\"020 123 4567\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.position").value("Striker"))
			.andExpect(jsonPath("$.payoutPhone").value("+233201234567"))
			.andExpect(jsonPath("$.name").value("Kwame Mensah"));
	}

	@Test
	void handlesAreUniqueIgnoringCase() throws Exception {
		var kwame = TestSignIn.as(mvc, "024 455 5123");
		var kojo = TestSignIn.as(mvc, "024 455 5124");
		mvc.perform(patch("/me").cookie(kwame).contentType(MediaType.APPLICATION_JSON).content("{\"handle\":\"kwame\"}"))
			.andExpect(status().isOk());

		mvc.perform(patch("/me").cookie(kojo).contentType(MediaType.APPLICATION_JSON).content("{\"handle\":\"KWAME\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.message").value("That username is taken. Try another."));

		mvc.perform(get("/handles/Kwame").cookie(kojo)).andExpect(jsonPath("$.available").value(false));
		mvc.perform(get("/handles/Kwame").cookie(kwame)).andExpect(jsonPath("$.available").value(true));
		mvc.perform(get("/handles/kojo")).andExpect(jsonPath("$.available").value(true));
		mvc.perform(get("/handles/k!")).andExpect(jsonPath("$.available").value(false));
	}

	@Test
	void badValuesGetMessagesWrittenForPeople() throws Exception {
		var kwame = TestSignIn.as(mvc, "024 455 5123");
		mvc.perform(patch("/me").cookie(kwame).contentType(MediaType.APPLICATION_JSON).content("{\"sports\":[\"cricket\"]}"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.error.message").value("Pick sports from the list."));
		mvc.perform(post("/me/onboarding").cookie(kwame).contentType(MediaType.APPLICATION_JSON).content("{\"sports\":[\"football\"]}"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.error.message").value("Add your name and a username to finish."));
	}

}
