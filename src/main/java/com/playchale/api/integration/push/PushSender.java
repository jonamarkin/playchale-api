package com.playchale.api.integration.push;

/**
 * Sends a notification to one browser. Only exists when push is set up (see {@link PushProperties}).
 */
public interface PushSender {

	/** What happened to one message. */
	enum Delivery {
		SENT,
		/** The subscription is gone (unsubscribed, or the app was removed): forget it. */
		GONE,
		/** Anything else: try again with the next notification. */
		FAILED
	}

	/** The public key the web app subscribes with. */
	String publicKey();

	/** Encrypts {@code payload} for the browser and sends it. Never throws. */
	Delivery send(PushTarget target, String payload);

}
