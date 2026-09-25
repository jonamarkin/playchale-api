package com.playchale.api;

import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Signs a test in the way the web app does: ask for a code, then send the dev profile's demo code. */
public final class TestSignIn {

	private TestSignIn() {
	}

	/** The session cookie for the player with this number, registering them if they're new. */
	public static Cookie as(MockMvc mvc, String phone) throws Exception {
		mvc.perform(post("/auth/codes").contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"%s\"}".formatted(phone)))
			.andExpect(status().isOk());
		var response = mvc.perform(post("/auth/sessions").contentType(MediaType.APPLICATION_JSON)
			.content("{\"phone\":\"%s\",\"code\":\"123456\"}".formatted(phone)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse();
		var token = response.getHeader("Set-Cookie").split(";")[0].split("=", 2)[1];
		return new Cookie("playchale_session", token);
	}

}
