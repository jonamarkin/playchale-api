package com.playchale.api.integration.email;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.core.io.ClassPathResource;
import org.springframework.web.util.HtmlUtils;

/**
 * Builds an email's HTML: a card from {@code resources/email/<name>.html}, inside the shell every
 * PlayChale email shares ({@code resources/email/layout.html}), in the web app's colours.
 *
 * <p>Templates are plain HTML with {@code {{name}}} placeholders. Every value is HTML-escaped as it
 * goes in, so nothing a person typed can change an email's markup.
 */
public final class EmailLayout {

	private static final Pattern COMMENT = Pattern.compile("<!--.*?-->\\s*", Pattern.DOTALL);

	private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\w+}}");

	private static final String LAYOUT = read("layout");

	private EmailLayout() {
	}

	/**
	 * @param webApp    the web app's address (e.g. https://playchale.com), for the logo and links
	 * @param card      the card's template, e.g. "sign-in-code" for resources/email/sign-in-code.html
	 * @param subject   the email's subject, also its HTML title
	 * @param preheader the line inboxes show after the subject
	 * @param footer    why the person got this email
	 * @param values    the card's placeholders
	 */
	public static String render(String webApp, String card, String subject, String preheader, String footer, Map<String, String> values) {
		var home = webApp.replaceAll("/+$", "");
		var content = fill(read(card), values);
		return fill(LAYOUT, Map.of("subject", subject, "preheader", preheader, "footer", footer, "webApp", home,
				"webAppName", URI.create(home).getHost()))
			// The card is our own markup, filled above; it goes in last so its text isn't filled twice.
			.replace("{{content}}", content);
	}

	private static String fill(String template, Map<String, String> values) {
		var out = template;
		for (var value : values.entrySet()) {
			out = out.replace("{{" + value.getKey() + "}}", HtmlUtils.htmlEscape(value.getValue()));
		}
		var left = PLACEHOLDER.matcher(out.replace("{{content}}", ""));
		if (left.find()) {
			throw new IllegalArgumentException("Email template left unfilled: " + left.group());
		}
		return out;
	}

	private static String read(String name) {
		try {
			// Comments are notes for whoever edits the template; they don't need to be emailed.
			return COMMENT.matcher(new ClassPathResource("email/" + name + ".html").getContentAsString(StandardCharsets.UTF_8)).replaceAll("");
		}
		catch (IOException e) {
			throw new UncheckedIOException("No email template " + name, e);
		}
	}

}
