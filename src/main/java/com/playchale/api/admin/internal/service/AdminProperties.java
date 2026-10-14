package com.playchale.api.admin.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The admin desk's settings ({@code playchale.admin.*}).
 *
 * @param url where the desk is, for the link in the emails that tell staff something is waiting
 *            (PLAYCHALE_ADMIN_URL)
 */
@ConfigurationProperties("playchale.admin")
public record AdminProperties(@DefaultValue("https://admin.playchale.com") String url) {
}
