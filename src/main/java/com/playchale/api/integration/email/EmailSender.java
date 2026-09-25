package com.playchale.api.integration.email;

/** Sends an email. The provider's adapter is the only class that knows its API. */
public interface EmailSender {

	/**
	 * @param to      the address
	 * @param subject the subject line
	 * @param text    the message, as plain text
	 */
	void send(String to, String subject, String text);

}
