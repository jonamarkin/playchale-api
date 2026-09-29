package com.playchale.api.auth.web;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.playchale.api.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Signing in with Google, with a key set of the test's own in place of Google's. */
@SpringBootTest(properties = "playchale.google.client-id=test-client.apps.googleusercontent.com")
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, GoogleSignInApiTest.Keys.class })
class GoogleSignInApiTest {

	static final RSAKey GOOGLE = key("google-1");

	static final RSAKey IMPOSTOR = key("google-1");

	private static RSAKey key(String id) {
		try {
			return new RSAKeyGenerator(2048).keyID(id).generate();
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	@TestConfiguration
	static class Keys {

		@Bean
		@Primary
		JWKSource<SecurityContext> testGoogleKeys() {
			return new ImmutableJWKSet<>(new JWKSet(GOOGLE.toPublicJWK()));
		}

	}

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void clean() {
		jdbc.sql("TRUNCATE users, sign_in_codes, sessions, rate_limits CASCADE").update();
	}

	private static String token(RSAKey key, String audience, String sub, String email, boolean verified, Instant expires) throws Exception {
		var claims = new JWTClaimsSet.Builder().issuer("https://accounts.google.com").audience(audience).subject(sub)
			.claim("email", email).claim("email_verified", verified).issueTime(new Date()).expirationTime(Date.from(expires)).build();
		var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
		jwt.sign(new RSASSASigner(key));
		return jwt.serialize();
	}

	private static String good(String sub, String email) throws Exception {
		return token(GOOGLE, "test-client.apps.googleusercontent.com", sub, email, true, Instant.now().plusSeconds(600));
	}

	private ResultActions google(String credential) throws Exception {
		return mvc.perform(post("/auth/google").contentType(MediaType.APPLICATION_JSON)
			.content("{\"credential\":\"%s\",\"country\":\"GH\"}".formatted(credential)));
	}

	@Test
	void theButtonIsOfferedWithTheClientId() throws Exception {
		mvc.perform(get("/auth/options")).andExpect(jsonPath("$.googleClientId").value("test-client.apps.googleusercontent.com"));
	}

	@Test
	void aGoogleAccountMakesAPlayChaleAccountAndOpensItAgain() throws Exception {
		var first = google(good("1001", "Ama.Serwaa@gmail.com"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.signInEmail").value("ama.serwaa@gmail.com"))
			.andExpect(jsonPath("$.google").value(true))
			.andExpect(jsonPath("$.onboarded").value(false))
			.andReturn().getResponse();
		var id = first.getContentAsString().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
		google(good("1001", "ama.serwaa@gmail.com")).andExpect(jsonPath("$.id").value(id));
	}

	@Test
	void anEmailAccountIsConnectedRatherThanDuplicated() throws Exception {
		mvc.perform(post("/auth/codes").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"kofi@gmail.com\"}"));
		var id = mvc.perform(post("/auth/sessions").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"kofi@gmail.com\",\"code\":\"123456\"}"))
			.andReturn().getResponse().getContentAsString().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

		var response = google(good("2002", "kofi@gmail.com"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id))
			.andExpect(jsonPath("$.google").value(true))
			.andReturn().getResponse();
		var session = new Cookie("playchale_session", response.getHeader("Set-Cookie").split(";")[0].split("=", 2)[1]);

		// Google is a way in of its own: the email can go.
		mvc.perform(delete("/me/sign-in-methods/email").cookie(session)).andExpect(status().isOk()).andExpect(jsonPath("$.signInEmail").doesNotExist());
		google(good("2002", "kofi@gmail.com")).andExpect(jsonPath("$.id").value(id));
	}

	@Test
	void tokensThatArentGooglesForUsAreRefused() throws Exception {
		var later = Instant.now().plusSeconds(600);
		google(token(GOOGLE, "someone-elses-app", "3003", "x@gmail.com", true, later))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.error.message").value("Signing in with Google didn’t work. Try again."));
		google(token(GOOGLE, "test-client.apps.googleusercontent.com", "3003", "x@gmail.com", true, Instant.now().minusSeconds(600)))
			.andExpect(status().isUnprocessableContent());
		google(token(IMPOSTOR, "test-client.apps.googleusercontent.com", "3003", "x@gmail.com", true, later))
			.andExpect(status().isUnprocessableContent());
		google(token(GOOGLE, "test-client.apps.googleusercontent.com", "3003", "x@gmail.com", false, later))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.error.message").value("Your Google account’s email address isn’t verified yet. Verify it with Google, or sign in with a code."));
		google("not-a-token").andExpect(status().isUnprocessableContent());
		assert jdbc.sql("SELECT count(*) FROM users").query(Long.class).single() == 0 : UUID.randomUUID();
	}

}
