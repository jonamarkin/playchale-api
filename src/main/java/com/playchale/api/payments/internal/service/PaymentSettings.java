package com.playchale.api.payments.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param webAppUrl the web app's address, e.g. https://playchale.com: a hosted checkout sends the
 *                  payer back to the game there when they're done
 */
@ConfigurationProperties("playchale.payments")
public record PaymentSettings(String webAppUrl) {
}
