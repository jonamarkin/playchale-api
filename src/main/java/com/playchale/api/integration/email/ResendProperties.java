package com.playchale.api.integration.email;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Resend's settings (https://resend.com). Set PLAYCHALE_RESEND_API_KEY and PLAYCHALE_RESEND_FROM
 * and sign-in codes go out by email in any profile; leave the key empty and a laptop logs them
 * instead.
 *
 * @param apiKey  an API key with sending access (re_...)
 * @param from    the sender, on a domain verified in Resend, e.g. {@code PlayChale <alert@playchale.com>}
 * @param baseUrl Resend's API, only changed in tests
 */
@ConfigurationProperties("playchale.resend")
public record ResendProperties(String apiKey, String from, @DefaultValue("https://api.resend.com") String baseUrl) {

}
