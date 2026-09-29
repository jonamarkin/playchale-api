package com.playchale.api.auth.web;

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
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** One account, a phone and an email: adding either with a code, and removing one while the other stays. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SignInMethodsApiTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void clean() {
		jdbc.sql("TRUNCATE users, sign_in_codes, sessions, rate_limits CASCADE").update();
	}

	private ResultActions requestCode(Cookie me, String json) throws Exception {
		return mvc.perform(post("/me/sign-in-methods/codes").cookie(me).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private ResultActions add(Cookie me, String json) throws Exception {
		return mvc.perform(post("/me/sign-in-methods").cookie(me).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private ResultActions signIn(String json) throws Exception {
		return mvc.perform(post("/auth/sessions").contentType(MediaType.APPLICATION_JSON).content(json));
	}

	@Test
	void aPhoneAccountAddsAnEmailAndSignsInWithEither() throws Exception {
		var kwame = TestSignIn.as(mvc, "024 455 5123");

		requestCode(kwame, "{\"email\":\"Kwame@Example.com\"}").andExpect(status().isOk());
		add(kwame, "{\"email\":\"kwame@example.com\",\"code\":\"000000\"}")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.error.message").value("That code isn’t right. Check the email and try again."));
		add(kwame, "{\"email\":\"kwame@example.com\",\"code\":\"123456\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.phone").value("+233244555123"))
			.andExpect(jsonPath("$.signInEmail").value("kwame@example.com"));

		// The email now opens the same account.
		mvc.perform(post("/auth/codes").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"kwame@example.com\"}"));
		signIn("{\"email\":\"kwame@example.com\",\"code\":\"123456\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.phone").value("+233244555123"));

		requestCode(kwame, "{\"email\":\"kwame@example.com\"}")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.error.message").value("That address is already on your account."));
	}

	@Test
	void aWayInThatSomeoneElseHasIsRefused() throws Exception {
		TestSignIn.as(mvc, "020 111 2222");
		var ama = TestSignIn.as(mvc, "024 455 5123");
		requestCode(ama, "{\"phone\":\"020 111 2222\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.message").value("That number is on another PlayChale account. To bring the two together, write to support@playchale.com."));
		// Even with a code (asked for when signing in to the other account): refused, not taken over.
		mvc.perform(post("/auth/codes").contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"020 111 2222\"}"));
		add(ama, "{\"phone\":\"020 111 2222\",\"code\":\"123456\"}").andExpect(status().isConflict());
	}

	@Test
	void aWayInCanGoWhileAnotherStays() throws Exception {
		var kwame = TestSignIn.as(mvc, "024 455 5123");
		mvc.perform(delete("/me/sign-in-methods/phone").cookie(kwame))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.error.message").value("Keep at least one way to sign in: add another before removing this one."));

		requestCode(kwame, "{\"email\":\"kwame@example.com\"}");
		add(kwame, "{\"email\":\"kwame@example.com\",\"code\":\"123456\"}").andExpect(status().isOk());
		mvc.perform(delete("/me/sign-in-methods/phone").cookie(kwame))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.phone").value(""))
			.andExpect(jsonPath("$.signInEmail").value("kwame@example.com"));

		// Changing number: a new phone takes the old one's place (here, back on after removing it).
		requestCode(kwame, "{\"phone\":\"020 999 8888\"}");
		add(kwame, "{\"phone\":\"020 999 8888\",\"code\":\"123456\"}").andExpect(jsonPath("$.phone").value("+233209998888"));
		mvc.perform(delete("/me/sign-in-methods/password").cookie(kwame)).andExpect(status().isNotFound());
	}

}
