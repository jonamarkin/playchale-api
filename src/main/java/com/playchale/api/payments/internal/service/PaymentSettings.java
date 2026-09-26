package com.playchale.api.payments.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param webAppUrl the web app's address, e.g. https://playchale.com: a hosted checkout sends the
 *                  payer back to the game there when they're done
 * @param inApp     whether players pay their share in the app (through the payment provider). Off,
 *                  they pay the host directly, by mobile money or cash, and the host marks it: how
 *                  PlayChale can launch before its payment provider is set up.
 */
@ConfigurationProperties("playchale.payments")
public record PaymentSettings(String webAppUrl, @DefaultValue("true") boolean inApp) {
}
