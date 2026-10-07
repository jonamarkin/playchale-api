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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
