package com.playchale.api.catalog.web;

import com.playchale.api.TestcontainersConfiguration;
import com.playchale.api.users.api.Terms;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The sport catalogue as the web app's Sport type reads it. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CatalogApiTest {

	@Autowired
	MockMvc mvc;

	@Test
	void sportsCarryTheirPositions() throws Exception {
		mvc.perform(get("/sports"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].id").value("football"))
			.andExpect(jsonPath("$[0].roles.label").value("Position"))
			.andExpect(jsonPath("$[0].roles.max").value(2))
			.andExpect(jsonPath("$[0].roles.options[0].id").value("goalkeeper"))
			.andExpect(jsonPath("$[0].roles.options[0].label").value("Goalkeeper"))
			// Only set when true (or present): the web app's optional fields.
			.andExpect(jsonPath("$[0].roles.options[0].exclusive").doesNotExist())
			.andExpect(jsonPath("$[0].roles.options[0].group").doesNotExist())
			.andExpect(jsonPath("$[0].roles.options[4].id").value("anywhere"))
			.andExpect(jsonPath("$[0].roles.options[4].exclusive").value(true))
			// Tennis has no positions, so the app doesn't ask.
			.andExpect(jsonPath("$[3].id").value("tennis"))
			.andExpect(jsonPath("$[3].roles").doesNotExist());
	}

	@Test
	void everyResponseCarriesTheSecurityHeaders() throws Exception {
		mvc.perform(get("/terms"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.version").value(Terms.CURRENT))
			.andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andExpect(header().string("X-Frame-Options", "DENY"))
			.andExpect(header().string("Referrer-Policy", "no-referrer"))
			.andExpect(header().string("Strict-Transport-Security", "max-age=31536000; includeSubDomains"));
		// Errors too.
		mvc.perform(get("/games/not-a-game")).andExpect(header().string("X-Content-Type-Options", "nosniff"));
	}

}
