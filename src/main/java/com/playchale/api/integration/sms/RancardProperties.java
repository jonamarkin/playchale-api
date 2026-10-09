package com.playchale.api.integration.sms;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Rancard's settings (https://unify.rancard.com). Set PLAYCHALE_RANCARD_API_KEY and
 * PLAYCHALE_RANCARD_SENDER_ID and sign-in codes go out by SMS in any profile, which turns on signing
 * in with a phone number; leave the key empty and a laptop logs them instead.
 *
 * @param apiKey     an API key generated on unify.rancard.com
 * @param senderId   the sender ID people see, up to 11 characters, created on unify.rancard.com and
 *                   approved by Rancard Support, e.g. {@code PlayChale}
 * @param baseUrl    Rancard's Bulk Messaging API, only changed in tests
 * @param balanceUrl Rancard's account API, for the SMS bundle balance, only changed in tests
 * @param lowBalance below this many credits the API warns, so the bundle is topped up before codes stop
 */
@ConfigurationProperties("playchale.rancard")
public record RancardProperties(String apiKey, String senderId,
		@DefaultValue("https://bulkmessagingapi.rancard.com") String baseUrl,
		@DefaultValue("https://unify-base.rancard.com") String balanceUrl,
		@DefaultValue("500") int lowBalance) {

}
