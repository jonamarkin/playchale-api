package com.playchale.api.users.web;

import com.playchale.api.TestSignIn;
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
				{"name":"Kwame Mensah","handle":"Kwame","sports":["football"],"roles":{"football":["forward"]},"area":"East Legon"}
				"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.onboarded").value(true))
			.andExpect(jsonPath("$.roles.football[0]").value("forward"))
			.andExpect(jsonPath("$.handle").value("kwame"))
			.andExpect(jsonPath("$.sports[0]").value("football"))
			.andExpect(jsonPath("$.area").value("East Legon"));

		mvc.perform(get("/auth/session").cookie(kwame)).andExpect(jsonPath("$.onboarded").value(true));

		// A web app from before positions per sport still sends the old field: accepted, and ignored.
		mvc.perform(patch("/me").cookie(kwame).contentType(MediaType.APPLICATION_JSON)
			.content("{\"position\":\"Striker\",\"payoutPhone\":\"020 123 4567\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.position").doesNotExist())
			.andExpect(jsonPath("$.roles.football[0]").value("forward"))
			.andExpect(jsonPath("$.payoutPhone").value("+233201234567"))
			.andExpect(jsonPath("$.name").value("Kwame Mensah"));
	}

	@Test
	void aShuffledFaceIsSavedAndPublic() throws Exception {
		var kwame = TestSignIn.as(mvc, "024 455 5123");
		mvc.perform(post("/me/onboarding").cookie(kwame).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Kwame\",\"handle\":\"kwame\",\"sports\":[\"football\"]}"))
			.andExpect(jsonPath("$.avatarSeed").doesNotExist());
		mvc.perform(patch("/me").cookie(kwame).contentType(MediaType.APPLICATION_JSON).content("{\"avatarSeed\":\"k3v9x1qz0b7m\"}"))
			.andExpect(jsonPath("$.avatarSeed").value("k3v9x1qz0b7m"));
		mvc.perform(get("/profiles/kwame")).andExpect(jsonPath("$.user.avatarSeed").value("k3v9x1qz0b7m"));
		mvc.perform(patch("/me").cookie(kwame).contentType(MediaType.APPLICATION_JSON).content("{\"avatarSeed\":\"\"}"))
			.andExpect(jsonPath("$.avatarSeed").doesNotExist());
		expectRefused(kwame, "{\"avatarSeed\":\"../../etc\"}", "Pick a face with Shuffle.");
	}

	@Test
	void positionsArePerSportAndPublic() throws Exception {
		var kwame = TestSignIn.as(mvc, "024 455 5123");
		mvc.perform(post("/me/onboarding").cookie(kwame).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Kwame\",\"handle\":\"kwame\",\"sports\":[\"football\"]}"))
			.andExpect(jsonPath("$.roles").isEmpty());

		// Adding a sport and its position in one go: sports are applied first.
		mvc.perform(patch("/me").cookie(kwame).contentType(MediaType.APPLICATION_JSON).content("""
				{"sports":["football","basketball","tennis"],"roles":{"football":["defender","midfielder"],"basketball":["anywhere"]}}
				"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.roles.football[0]").value("defender"))
			.andExpect(jsonPath("$.roles.football[1]").value("midfielder"))
			.andExpect(jsonPath("$.roles.basketball[0]").value("anywhere"))
			.andExpect(jsonPath("$.roles.tennis").doesNotExist());

		// Anyone can see them.
		mvc.perform(get("/profiles/kwame")).andExpect(jsonPath("$.user.roles.football[0]").value("defender"));

		// Dropping a sport drops its positions.
		mvc.perform(patch("/me").cookie(kwame).contentType(MediaType.APPLICATION_JSON).content("{\"sports\":[\"basketball\"]}"))
			.andExpect(jsonPath("$.roles.football").doesNotExist())
			.andExpect(jsonPath("$.roles.basketball[0]").value("anywhere"));
	}

	@Test
	void positionsComeFromEachSportsOwnList() throws Exception {
		var kwame = TestSignIn.as(mvc, "024 455 5123");
		mvc.perform(patch("/me").cookie(kwame).contentType(MediaType.APPLICATION_JSON).content("{\"sports\":[\"football\",\"tennis\"]}"));

		expectRefused(kwame, "{\"roles\":{\"football\":[\"striker\"]}}", "Pick positions from the list.");
		expectRefused(kwame, "{\"roles\":{\"football\":[\"guard\"]}}", "Pick positions from the list.");
		expectRefused(kwame, "{\"roles\":{\"football\":[\"goalkeeper\",\"defender\",\"forward\"]}}", "Pick up to 2 positions for Football.");
		expectRefused(kwame, "{\"roles\":{\"football\":[\"anywhere\",\"forward\"]}}", "Anywhere can’t go with other positions.");
		expectRefused(kwame, "{\"roles\":{\"basketball\":[\"guard\"]}}", "Add Basketball to your sports first.");
		expectRefused(kwame, "{\"roles\":{\"tennis\":[\"singles\"]}}", "Tennis doesn’t have positions to pick.");
	}

	private void expectRefused(Cookie who, String body, String message) throws Exception {
		mvc.perform(patch("/me").cookie(who).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.error.code").value("invalid"))
			.andExpect(jsonPath("$.error.message").value(message));
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
