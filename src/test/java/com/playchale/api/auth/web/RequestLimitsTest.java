package com.playchale.api.auth.web;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** What stops one client overwhelming the API: the size of what it sends, and how fast one account changes things. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RequestLimitsTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void emptyTables() {
		jdbc.sql("TRUNCATE users, sign_in_codes, sessions, notifications, rate_limits CASCADE").update();
	}

	@Test
	void aBodyOverAMegabyteIsRefusedBeforeAnythingReadsIt() throws Exception {
		mvc.perform(post("/auth/codes").contentType(MediaType.APPLICATION_JSON).content(new byte[1024 * 1024 + 1]))
			.andExpect(status().isPayloadTooLarge())
			.andExpect(jsonPath("$.error.code").value("invalid"));
	}

	@Test
	void oneAddressCanOnlyAskSoFastAndNobodyElseIsSlowed() throws Exception {
		RequestPostProcessor flooding = r -> {
			r.setRemoteAddr("203.0.113.50");
			return r;
		};
		int allowed = 0;
		for (int i = 0; i < 120; i++) {
			var status = mvc.perform(get("/sports").with(flooding)).andReturn().getResponse().getStatus();
			if (status == 200) {
				allowed++;
			}
			else {
				assertThat(status).isEqualTo(429);
			}
		}
		assertThat(allowed).as("the burst, and a little refill while the loop ran").isBetween(60, 100);
		mvc.perform(get("/sports").with(flooding))
			.andExpect(status().isTooManyRequests())
			.andExpect(header().string("Retry-After", "2"))
			.andExpect(jsonPath("$.error.message").value("Too many requests at once. Wait a moment and try again."));

		mvc.perform(get("/sports").with(r -> {
			r.setRemoteAddr("203.0.113.51");
			return r;
		})).andExpect(status().isOk());
		mvc.perform(get("/actuator/health").with(flooding)).andExpect(status().isOk());
	}

	@Test
	void anAccountCanOnlyChangeThingsSoFast() throws Exception {
		var me = TestSignIn.as(mvc, "+233244555190");
		for (int i = 0; i < CurrentUserArgumentResolver.WRITES_PER_TEN_MINUTES; i++) {
			mvc.perform(post("/notifications/read-all").cookie(me)).andExpect(status().is2xxSuccessful());
		}
		mvc.perform(post("/notifications/read-all").cookie(me))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.message").value("You’re doing that too fast. Wait a few minutes, then try again."));
		// Looking is never limited, and nobody else is slowed down.
		mvc.perform(get("/notifications").cookie(me)).andExpect(status().isOk());
		var someoneElse = TestSignIn.as(mvc, "+233244555191");
		mvc.perform(post("/notifications/read-all").cookie(someoneElse)).andExpect(status().is2xxSuccessful());
	}

}
