package com.playchale.api.payments.web;

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

/** Paying over HTTP, as the web app's pay sheet does: start, then poll. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentApiTest {

	/** The web app's Payment type. */
	private static final Set<String> PAYMENT_FIELDS = Set.of("id", "gameId", "userId", "method", "amount", "fee", "status", "reference",
			"payerPhone", "createdAt", "failureReason");

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ObjectMapper json;

	@BeforeEach
	void emptyTables() {
		jdbc.sql("TRUNCATE users, sign_in_codes, sessions, games, game_participants, notifications, payments, movements CASCADE").update();
	}

	@Test
	void payingAShareAndSeeingItOnTheStatement() throws Exception {
		var kwame = TestSignIn.as(mvc, "024 455 5123");
		var game = json.readTree(mvc.perform(post("/games").cookie(kwame).contentType(MediaType.APPLICATION_JSON).content("""
				{"sport":"football","format":"5-a-side","startsAt":"2030-06-02T16:00:00Z","durationMinutes":60,
				 "venue":{"kind":"unlisted","name":"Legon Park"},"capacity":10,"totalCost":25000,"visibility":"public"}
				""")).andReturn().getResponse().getContentAsString()).get("id").asString();
		var kojo = TestSignIn.as(mvc, "024 455 5124");
		mvc.perform(post("/games/" + game + "/players").cookie(kojo));

		var started = mvc.perform(post("/payments").cookie(kojo).contentType(MediaType.APPLICATION_JSON)
			.content("{\"gameId\":\"%s\",\"method\":\"momo-mtn\",\"payerPhone\":\"+233244555124\"}".formatted(game)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("pending"))
			.andExpect(jsonPath("$.fee").value(0))
			.andReturn().getResponse().getContentAsString();
		var payment = json.readTree(started);
		assertThat(PAYMENT_FIELDS).containsAll(payment.propertyNames());
		var id = payment.get("id").asString();

		mvc.perform(get("/payments/" + id).cookie(kojo)).andExpect(jsonPath("$.id").value(id));
		mvc.perform(get("/payments/" + id).cookie(kwame)).andExpect(status().isNotFound());
		mvc.perform(get("/payments/" + id + "/status").cookie(kojo)).andExpect(jsonPath("$.status").value("pending"));

		Thread.sleep(2700);
		mvc.perform(get("/payments/" + id + "/status").cookie(kojo)).andExpect(jsonPath("$.status").value("succeeded"));
		mvc.perform(get("/me/statement").cookie(kwame))
			.andExpect(jsonPath("$[0].direction").value("in"))
			.andExpect(jsonPath("$[0].kind").value("share"))
			.andExpect(jsonPath("$[0].amount").value(2500))
			.andExpect(jsonPath("$[0].counterparty.phone").value(""))
			.andExpect(jsonPath("$[0].gameTitle").value("5-a-side football"));
	}

}
