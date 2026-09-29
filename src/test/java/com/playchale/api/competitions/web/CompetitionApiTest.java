package com.playchale.api.competitions.web;

import java.util.Set;
import java.util.stream.StreamSupport;

import com.playchale.api.TestSignIn;
import jakarta.servlet.http.Cookie;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A league over HTTP, with the JSON the web app's competition pages expect. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CompetitionApiTest {

	/** The web app's CompetitionView. */
	private static final Set<String> VIEW_FIELDS = Set.of("id", "name", "sport", "format", "organiserId", "venue", "startsAt",
			"durationMinutes", "status", "points", "createdAt", "organiser", "teams", "table", "fixtures", "rounds", "requests", "playerLists",
			"country", "currency", "timezone", "scorers", "organisers", "structure", "bracket");

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ObjectMapper json;

	@BeforeEach
	void emptyTables() {
		jdbc.sql("""
				TRUNCATE users, sign_in_codes, sessions, games, game_participants, notifications, game_results,
				         competitions, teams, team_members, team_join_requests, competition_entries, entry_players CASCADE
				""").update();
	}

	@Test
	void settingUpALeague() throws Exception {
		var sam = TestSignIn.as(mvc, "024 455 5120");
		var created = json.readTree(mvc.perform(post("/competitions").cookie(sam).contentType(MediaType.APPLICATION_JSON).content("""
				{"name":"Office League","sport":"football","format":"5-a-side","venue":{"kind":"unlisted","name":"Legon Park"},
				 "startsAt":"2030-06-01T09:00:00.000Z","durationMinutes":60}
				""")).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("draft"))
			.andExpect(jsonPath("$.points.win").value(3)).andReturn().getResponse().getContentAsString());
		assertThat(VIEW_FIELDS).containsAll(created.propertyNames());
		var id = created.get("id").asString();

		for (var team : new String[] { "Reds", "Blues", "Greens" }) {
			mvc.perform(post("/competitions/" + id + "/teams").cookie(sam).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"%s\",\"playerIds\":[]}".formatted(team)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.teams[-1].playerIds").isEmpty());
		}
		mvc.perform(post("/competitions/" + id + "/fixtures").cookie(sam))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("running"))
			.andExpect(jsonPath("$.fixtures.length()").value(3))
			.andExpect(jsonPath("$.fixtures[0].fixture.round").value(1))
			.andExpect(jsonPath("$.fixtures[0].fixtureTeams.home.joinToken").value(""))
			.andExpect(jsonPath("$.table.length()").value(3))
			.andExpect(jsonPath("$.table[0].team.name").exists());

		mvc.perform(get("/competitions")).andExpect(jsonPath("$[0].id").value(id)).andExpect(jsonPath("$[0].teams[0].joinToken").value(""));
		mvc.perform(get("/competitions/" + id).cookie(sam)).andExpect(jsonPath("$.teams[0].joinToken").isNotEmpty());
		mvc.perform(get("/competitions/0199f000-0000-7000-8000-000000000000")).andExpect(status().isNotFound());
	}

	/**
	 * A league page anyone can open: the table, the fixtures and the scorers, but nothing private.
	 * Its link is shared with people who have never signed in, so this is what a stranger may see.
	 */
	@Test
	void aLeagueIsReadableByAnyoneWithoutItsPrivateDetails() throws Exception {
		var kojo = TestSignIn.as(mvc, "024 455 5120");
		var kwame = TestSignIn.as(mvc, "024 455 5121");
		var ama = TestSignIn.as(mvc, "024 455 5122");
		var kwameId = idOf(kwame);
		var amaId = idOf(ama);

		var id = json.readTree(mvc.perform(post("/competitions").cookie(kojo).contentType(MediaType.APPLICATION_JSON).content("""
				{"name":"Interco League","sport":"football","format":"5-a-side","venue":{"kind":"unlisted","name":"Legon Park"},
				 "startsAt":"2030-06-01T09:00:00.000Z","durationMinutes":60}
				""")).andReturn().getResponse().getContentAsString()).get("id").asString();
		addTeam(kojo, id, "Reds", kwameId);
		addTeam(kojo, id, "Blues", amaId);
		addTeam(kojo, id, "Greens", idOf(TestSignIn.as(mvc, "024 455 5123")));
		var drawn = json.readTree(mvc.perform(post("/competitions/" + id + "/fixtures").cookie(kojo))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		// The round Reds play Blues, whichever way the draw put them.
		var fixture = StreamSupport.stream(drawn.get("fixtures").spliterator(), false)
			.filter(f -> Set.of(f.get("fixtureTeams").get("home").get("name").asString(),
					f.get("fixtureTeams").get("away").get("name").asString()).equals(Set.of("Reds", "Blues")))
			.findFirst().orElseThrow().get("id").asString();

		// That fixture has been played: results only go in once the game has kicked off.
		jdbc.sql("UPDATE games SET starts_at = now() - interval '2 hours' WHERE id = :id").param("id", java.util.UUID.fromString(fixture)).update();

		// Kwame scores twice and sets one up; Ama scores once for the other side.
		mvc.perform(put("/games/" + fixture + "/result").cookie(kojo).contentType(MediaType.APPLICATION_JSON).content("""
				{"homeScore":2,"awayScore":1,"sides":{"home":["%s"],"away":["%s"]},
				 "scorers":[{"userId":"%s","goals":2,"assists":1},{"userId":"%s","goals":1}]}
				""".formatted(kwameId, amaId, kwameId, amaId))).andExpect(status().isOk());

		// Signed out: the whole league is readable.
		var page = mvc.perform(get("/competitions/" + id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Interco League"))
			.andExpect(jsonPath("$.table[0].played").value(1))
			.andExpect(jsonPath("$.fixtures.length()").value(3))
			.andExpect(jsonPath("$.scorers.length()").value(2))
			// Best first, and each player's squad is named.
			.andExpect(jsonPath("$.scorers[0].player.id").value(kwameId))
			.andExpect(jsonPath("$.scorers[0].goals").value(2))
			.andExpect(jsonPath("$.scorers[0].assists").value(1))
			.andExpect(jsonPath("$.scorers[0].games").value(1))
			.andExpect(jsonPath("$.scorers[0].teamName").value("Reds"))
			.andExpect(jsonPath("$.scorers[1].player.id").value(amaId))
			// Nothing private: no numbers, no squad links, no way to pay anyone.
			.andExpect(jsonPath("$.scorers[0].player.phone").value(""))
			.andExpect(jsonPath("$.teams[0].joinToken").value(""))
			.andExpect(jsonPath("$.teams[0].players[0].phone").value(""))
			.andExpect(jsonPath("$.teams[0].players[0].payoutPhone").doesNotExist())
			.andExpect(jsonPath("$.organiser.phone").value(""))
			.andExpect(jsonPath("$.fixtures[0].hostPayoutPhone").doesNotExist())
			.andReturn().getResponse().getContentAsString();
		assertThat(VIEW_FIELDS).containsAll(json.readTree(page).propertyNames());

		// The organiser still gets the private parts.
		mvc.perform(get("/competitions/" + id).cookie(kojo))
			.andExpect(jsonPath("$.teams[0].joinToken").isNotEmpty())
			.andExpect(jsonPath("$.scorers[0].goals").value(2));
	}

	/** A committee runs the league together, but only its owner decides who's on it. */
	@Test
	void aLeagueCanBeRunByMoreThanOnePerson() throws Exception {
		var kojo = TestSignIn.as(mvc, "024 455 5120");
		var kwame = TestSignIn.as(mvc, "024 455 5121");
		var ama = TestSignIn.as(mvc, "024 455 5122");
		var kwameId = idOf(kwame);
		var amaId = idOf(ama);

		var id = json.readTree(mvc.perform(post("/competitions").cookie(kojo).contentType(MediaType.APPLICATION_JSON).content("""
				{"name":"Staff League","sport":"football","format":"5-a-side","venue":{"kind":"unlisted","name":"Legon Park"},
				 "startsAt":"2030-06-01T09:00:00.000Z","durationMinutes":60}
				""")).andReturn().getResponse().getContentAsString()).get("id").asString();

		// Kwame can't touch it yet.
		mvc.perform(post("/competitions/" + id + "/teams").cookie(kwame).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Reds\",\"playerIds\":[]}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.message").value("Only the organiser can change this league."));

		mvc.perform(post("/competitions/" + id + "/organisers").cookie(kojo).contentType(MediaType.APPLICATION_JSON)
			.content("{\"userId\":\"%s\"}".formatted(kwameId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.organisers.length()").value(1))
			.andExpect(jsonPath("$.organisers[0].id").value(kwameId));

		// Now he runs it: he can add teams, but not change who else runs it.
		mvc.perform(post("/competitions/" + id + "/teams").cookie(kwame).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Reds\",\"playerIds\":[]}")).andExpect(status().isOk());
		mvc.perform(post("/competitions/" + id + "/organisers").cookie(kwame).contentType(MediaType.APPLICATION_JSON)
			.content("{\"userId\":\"%s\"}".formatted(amaId)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.message").value("Only the organiser who set this league up can change who runs it."));

		// The owner stays the owner, and can take the controls back.
		mvc.perform(post("/competitions/" + id + "/organisers").cookie(kojo).contentType(MediaType.APPLICATION_JSON)
			.content("{\"userId\":\"%s\"}".formatted(idOf(kojo))))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.error.message").value("They already run this league."));
		mvc.perform(delete("/competitions/" + id + "/organisers/" + idOf(kojo)).cookie(kojo))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.error.message").value("The organiser who set the league up can’t be removed."));
		mvc.perform(delete("/competitions/" + id + "/organisers/" + kwameId).cookie(kojo))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.organisers").isEmpty());
		mvc.perform(delete("/competitions/" + id + "/organisers/" + kwameId).cookie(kojo)).andExpect(status().isNotFound());
	}

	/** A cup from the draw to the trophy: byes, a tie on penalties, and each round drawing itself. */
	@Test
	void aKnockoutRunsItselfFromTheDrawToTheWinner() throws Exception {
		var kojo = TestSignIn.as(mvc, "024 455 5140");
		var id = json.readTree(mvc.perform(post("/competitions").cookie(kojo).contentType(MediaType.APPLICATION_JSON).content("""
				{"name":"Staff Cup","sport":"football","format":"5-a-side","structure":"knockout","playerLists":"optional",
				 "venue":{"kind":"unlisted","name":"Legon Park"},"startsAt":"2030-06-01T09:00:00.000Z","durationMinutes":60}
				""")).andExpect(status().isCreated())
			.andExpect(jsonPath("$.structure").value("knockout"))
			.andReturn().getResponse().getContentAsString()).get("id").asString();

		// Six teams in a bracket of eight: the first two entered get a bye.
		for (var team : new String[] { "Finance", "Legal", "Sales", "Tech", "HR", "Ops" }) {
			mvc.perform(post("/competitions/" + id + "/teams").cookie(kojo).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"%s\",\"playerIds\":[]}".formatted(team))).andExpect(status().isOk());
		}

		var drawn = json.readTree(mvc.perform(post("/competitions/" + id + "/fixtures").cookie(kojo))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("running"))
			// Two ties now; the rest of the bracket is there, waiting.
			.andExpect(jsonPath("$.fixtures.length()").value(2))
			.andExpect(jsonPath("$.bracket.length()").value(3))
			.andExpect(jsonPath("$.bracket[0].name").value("Quarter-finals"))
			.andExpect(jsonPath("$.bracket[0].ties").value(4))
			// Two ties plus the two byes, in bracket order: a bye is through without playing.
			.andExpect(jsonPath("$.bracket[0].played.length()").value(4))
			.andExpect(jsonPath("$.bracket[0].played[0].bye").value(true))
			.andExpect(jsonPath("$.bracket[0].played[0].home.name").value("Finance"))
			.andExpect(jsonPath("$.bracket[0].played[2].bye").value(true))
			.andExpect(jsonPath("$.bracket[0].played[2].home.name").value("Legal"))
			.andExpect(jsonPath("$.bracket[1].name").value("Semi-finals"))
			.andExpect(jsonPath("$.bracket[2].name").value("Final"))
			.andExpect(jsonPath("$.bracket[2].played").isEmpty())
			.andReturn().getResponse().getContentAsString());

		// Tech beat HR; Sales and Ops end level and Ops go through on penalties.
		var first = drawn.get("bracket").get(0).get("played");
		win(kojo, first.get(1).get("gameId").asString(), 2, 0, null, null);
		var levelTie = first.get(3).get("gameId").asString();
		played(levelTie);
		mvc.perform(put("/games/" + levelTie + "/result").cookie(kojo).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"homeScore":1,"awayScore":1,"sides":{"home":[],"away":[]}}
					"""))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.error.message").value("This tie ended level. Add the penalty shootout to say who went through."));
		mvc.perform(put("/games/" + levelTie + "/result").cookie(kojo).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"homeScore":1,"awayScore":1,"sides":{"home":[],"away":[]},"homePenalties":3,"awayPenalties":3}
					"""))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.error.message").value("A shootout can’t end level either. Check the penalties."));
		win(kojo, levelTie, 1, 1, 3, 4);

		// Both quarter-finals done, so the semis are drawn: the two byes meet the two winners.
		var semis = json.readTree(mvc.perform(get("/competitions/" + id).cookie(kojo)).andReturn().getResponse().getContentAsString());
		assertThat(semis.get("bracket").get(1).get("played")).hasSize(2);
		assertThat(semis.get("fixtures")).hasSize(4);
		// The score stays what happened on the pitch; the shootout is kept beside it.
		var settled = semis.get("bracket").get(0).get("played").get(3);
		assertThat(settled.get("homeScore").asInt()).isEqualTo(1);
		assertThat(settled.get("awayPenalties").asInt()).isEqualTo(4);
		assertThat(settled.get("winnerId").asString()).isEqualTo(settled.get("away").get("id").asString());

		for (var tie : semis.get("bracket").get(1).get("played")) {
			win(kojo, tie.get("gameId").asString(), 3, 1, null, null);
		}
		var last = json.readTree(mvc.perform(get("/competitions/" + id).cookie(kojo)).andReturn().getResponse().getContentAsString());
		assertThat(last.get("bracket").get(2).get("played")).hasSize(1);
		assertThat(last.get("status").asString()).isEqualTo("running");

		win(kojo, last.get("bracket").get(2).get("played").get(0).get("gameId").asString(), 2, 1, null, null);
		mvc.perform(get("/competitions/" + id).cookie(kojo))
			.andExpect(jsonPath("$.status").value("finished"))
			.andExpect(jsonPath("$.bracket[2].played[0].winnerId").isNotEmpty());
	}

	/** Moves a fixture into the past: a result only goes in once the game has kicked off. */
	private void played(String gameId) {
		jdbc.sql("UPDATE games SET starts_at = now() - interval '2 hours' WHERE id = :id")
			.param("id", java.util.UUID.fromString(gameId)).update();
	}

	/** Records a tie's result, once it's been played. */
	private void win(Cookie organiser, String gameId, int home, int away, Integer homePens, Integer awayPens) throws Exception {
		played(gameId);
		var penalties = homePens == null ? "" : ",\"homePenalties\":%d,\"awayPenalties\":%d".formatted(homePens, awayPens);
		mvc.perform(put("/games/" + gameId + "/result").cookie(organiser).contentType(MediaType.APPLICATION_JSON)
			.content("{\"homeScore\":%d,\"awayScore\":%d,\"sides\":{\"home\":[],\"away\":[]}%s}".formatted(home, away, penalties)))
			.andExpect(status().isOk());
	}

	private String idOf(Cookie session) throws Exception {
		return json.readTree(mvc.perform(get("/auth/session").cookie(session)).andReturn().getResponse().getContentAsString())
			.get("id").asString();
	}

	private void addTeam(Cookie organiser, String competition, String name, String captain) throws Exception {
		mvc.perform(post("/competitions/" + competition + "/teams").cookie(organiser).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"%s\",\"playerIds\":[\"%s\"]}".formatted(name, captain))).andExpect(status().isOk());
	}

}
