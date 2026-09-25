package com.playchale.api.auth;

/**
 * Delivers a sign-in code to a phone. An interface, so development can log codes while production
 * texts them, and tests can capture them, without the sign-in logic changing.
 */
public interface CodeSender {

	void send(String phone, String code);

}
