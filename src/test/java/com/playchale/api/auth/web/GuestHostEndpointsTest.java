package com.playchale.api.auth.web;

import java.util.TreeSet;

import com.playchale.api.TestcontainersConfiguration;
import com.playchale.api.shared.security.GuestHostsAllowed;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Everything a guest host (hosting without an account) can reach, in one list: adding to it is a
 * decision about what someone nobody has checked may do, so it's made here, on purpose.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class GuestHostEndpointsTest {

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	RequestMappingHandlerMapping mappings;

	@Test
	void onlyTheseEndpointsLetAGuestHostIn() {
		var allowed = new TreeSet<String>();
		mappings.getHandlerMethods().forEach((info, method) -> {
			if (method.hasMethodAnnotation(GuestHostsAllowed.class) || method.getBeanType().isAnnotationPresent(GuestHostsAllowed.class)) {
				info.getMethodsCondition().getMethods().forEach(verb -> info.getPatternValues().forEach(path -> allowed.add(verb + " " + path)));
			}
		});
		assertThat(allowed).containsExactly(
				"DELETE /games/{id}/messages/{messageId}",
				"DELETE /games/{id}/players/{player}",
				"DELETE /me",
				"GET /auth/session",
				"GET /games/{id}",
				"GET /games/{id}/messages",
				"GET /me/games",
				"GET /me/push",
				"GET /me/series",
				"GET /notifications",
				"PATCH /games/{id}",
				"PATCH /me/push",
				"PATCH /series/{id}",
				"POST /auth/host-keys",
				"POST /games",
				"POST /games/{id}/cancellation",
				"POST /games/{id}/guests",
				"POST /games/{id}/messages",
				"POST /games/{id}/players/{player}/attendance",
				"POST /games/{id}/players/{player}/cash",
				"POST /games/{id}/reminders",
				"POST /games/{id}/repeat",
				"POST /games/{id}/series",
				"POST /me/push/subscriptions",
				"POST /me/push/unsubscriptions",
				"POST /notifications/read-all",
				"POST /series/{id}/restart",
				"POST /series/{id}/stop",
				"PUT /games/{id}/result");
	}

}
