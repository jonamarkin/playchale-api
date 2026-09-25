package com.playchale.api.profiles.web;

import java.util.Set;

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
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PlayerProfileApiTest {

	/** The web app's PlayerProfile type (webapp/app/types/domain.ts). If this changes, both must. */
	private static final Set<String> PROFILE_FIELDS = Set.of("user", "stats", "form", "bySport", "teams");

	private static final Set<String> STATS_FIELDS = Set.of("games", "wins", "goals", "assists", "points", "setsWon");

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
	void aProfileIsPublicButPhoneNumbersAreOnlyForTheirOwner() throws Exception {
		var kwame = TestSignIn.as(mvc, "024 455 5123");
		var body = mvc.perform(post("/me/onboarding").cookie(kwame).contentType(MediaType.APPLICATION_JSON).content("""
				{"name":"Kwame Mensah","handle":"kwame","sports":["football","tennis"],"payoutPhone":"0201234567"}
				""")).andReturn().getResponse().getContentAsString();
		var id = json.readTree(body).get("id").asString();

		var signedOut = mvc.perform(get("/profiles/Kwame"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.user.name").value("Kwame Mensah"))
			.andExpect(jsonPath("$.user.phone").value(""))
			.andExpect(jsonPath("$.user.payoutPhone").doesNotExist())
			.andExpect(jsonPath("$.bySport[0].sport").value("football"))
			.andExpect(jsonPath("$.bySport[1].sport").value("tennis"))
			.andExpect(jsonPath("$.stats.games").value(0))
			.andReturn().getResponse().getContentAsString();
		var profile = json.readTree(signedOut);
		assertThat(profile.propertyNames()).containsExactlyInAnyOrderElementsOf(PROFILE_FIELDS);
		assertThat(profile.get("stats").propertyNames()).containsExactlyInAnyOrderElementsOf(STATS_FIELDS);

		mvc.perform(get("/users/" + id + "/profile").cookie(kwame))
			.andExpect(jsonPath("$.user.phone").value("+233244555123"))
			.andExpect(jsonPath("$.user.payoutPhone").value("+233201234567"));

		var kojo = TestSignIn.as(mvc, "024 455 5124");
		mvc.perform(get("/users/" + id + "/profile").cookie(kojo)).andExpect(jsonPath("$.user.phone").value(""));
		mvc.perform(get("/users/" + id + "/history")).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
	}

	@Test
	void unknownPlayersAreNotFound() throws Exception {
		mvc.perform(get("/profiles/nobody")).andExpect(status().isNotFound());
		mvc.perform(get("/users/0199f000-0000-7000-8000-000000000000/profile")).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.message").value("That player could not be found."));
		mvc.perform(get("/users/u-kwame/profile")).andExpect(status().isNotFound());
	}

	@Test
	void theSportsCatalogMatchesTheWebApp() throws Exception {
		mvc.perform(get("/sports"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(4))
			.andExpect(jsonPath("$[0].id").value("football"))
			.andExpect(jsonPath("$[0].formats[0]").value("5-a-side"))
			.andExpect(jsonPath("$[3].formats[1]").value("Doubles"));
	}

}
