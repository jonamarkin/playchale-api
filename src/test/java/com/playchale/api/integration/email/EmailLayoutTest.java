package com.playchale.api.integration.email;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class EmailLayoutTest {

	private static String signInCode(String code) {
		return EmailLayout.render("https://playchale.com/", "sign-in-code", "Your PlayChale sign-in code: " + code, "Your code is " + code,
				"Why you got this.", Map.of("code", code));
	}

	@Test
	void putsTheCardInTheBrandedShell() {
		var html = signInCode("482913");

		assertThat(html).contains("<title>Your PlayChale sign-in code: 482913</title>", ">482913<",
				"src=\"https://playchale.com/icons/icon-192.png\"", ">playchale.com</a>", "Why you got this.");
		assertThat(html).as("every placeholder filled").doesNotContain("{{");
		assertThat(html).as("notes for editors aren't emailed").doesNotContain("<!--");
	}

	@Test
	void valuesCantChangeTheMarkup() {
		assertThat(signInCode("<b>1</b>")).contains("&lt;b&gt;1&lt;/b&gt;").doesNotContain("<b>1</b>");
	}

	@Test
	void aMissingValueIsAnErrorNotABlank() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> EmailLayout.render("https://playchale.com", "sign-in-code", "s", "p", "f", Map.of()))
			.withMessageContaining("{{code}}");
	}

}
