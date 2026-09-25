package com.playchale.api.integration.sms;

/** Sends a text message. The provider's adapter is the only class that knows its API. */
public interface SmsSender {

	/**
	 * @param phone   E.164, e.g. +233241234567
	 * @param message the text, as the person will read it
	 */
	void send(String phone, String message);

}
