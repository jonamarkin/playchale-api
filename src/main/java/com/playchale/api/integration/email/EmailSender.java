package com.playchale.api.integration.email;

/** Sends an email. The provider's adapter is the only class that knows its API. */
public interface EmailSender {

	void send(Email email);

}
