package com.playchale.api.notifications.web;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Email settings over HTTP, and the link in a footer that stops them with nobody signed in. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EmailApiTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void emptyTables() {
		jdbc.sql("TRUNCATE users, sign_in_codes, sessions, email_preferences CASCADE").update();
	}

	@Test
	void marketingIsOffUntilSomeoneTurnsItOn() throws Exception {
		var kojo = TestSignIn.as(mvc, "024 455 5124");
		mvc.perform(get("/me/email")).andExpect(status().isUnauthorized());
		mvc.perform(get("/me/email").cookie(kojo))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.marketing").value(false))
			.andExpect(jsonPath("$.muted").isEmpty())
			.andExpect(jsonPath("$.categories").isNotEmpty());

		mvc.perform(patch("/me/email").cookie(kojo).contentType(MediaType.APPLICATION_JSON).content("{\"marketing\":true}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.marketing").value(true));

		// The date it was agreed is the record an enquiry asks for, so it is kept, not just the switch.
		assertThat(jdbc.sql("SELECT opted_in_at FROM email_preferences").query(Object.class).single()).isNotNull();
	}

	@Test
	void aCategoryCanBeKeptOutOfTheInboxAndRubbishIsRefused() throws Exception {
		var kojo = TestSignIn.as(mvc, "024 455 5124");
		mvc.perform(patch("/me/email").cookie(kojo).contentType(MediaType.APPLICATION_JSON).content("{\"muted\":[\"payments\"]}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.muted[0]").value("payments"));
		mvc.perform(patch("/me/email").cookie(kojo).contentType(MediaType.APPLICATION_JSON).content("{\"muted\":[\"horoscopes\"]}"))
			.andExpect(status().isUnprocessableEntity());
	}

	@Test
	void theLinkInAFooterUnsubscribesWithNobodySignedIn() throws Exception {
		var kojo = TestSignIn.as(mvc, "024 455 5124");
		mvc.perform(patch("/me/email").cookie(kojo).contentType(MediaType.APPLICATION_JSON).content("{\"marketing\":true}"))
			.andExpect(status().isOk());
		var token = jdbc.sql("SELECT token FROM email_preferences").query(String.class).single();

		// No cookie: this is someone tapping a link in their mail app.
		mvc.perform(post("/email/unsubscribe").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"%s\"}".formatted(token)))
			.andExpect(status().isOk());
		mvc.perform(get("/me/email").cookie(kojo))
			.andExpect(jsonPath("$.marketing").value(false))
			.andExpect(jsonPath("$.digest").value("off"));
	}

	@Test
	void aTokenThatMeansNothingIsAnsweredLikeOneThatWorked() throws Exception {
		// Nothing to learn by trying other people's tokens, and a rotated link shouldn't show an error.
		mvc.perform(post("/email/unsubscribe").contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\":\"00000000-0000-0000-0000-000000000000\"}")).andExpect(status().isOk());
		mvc.perform(post("/email/unsubscribe").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"not-a-token\"}"))
			.andExpect(status().isUnprocessableEntity());
	}

}
