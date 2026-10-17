package com.playchale.api.admin.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The admin desk's settings ({@code playchale.admin.*}).
 *
 * @param url    where the desk is, for the link in the emails that tell staff something is waiting
 *               (PLAYCHALE_ADMIN_URL)
 * @param alerts whether those emails go out at all (PLAYCHALE_ADMIN_ALERTS). On unless switched off;
 *               off, staff see what's waiting from the counts on the desk's tabs, and every other
 *               email (sign-in codes, players' own) carries on as before
 */
@ConfigurationProperties("playchale.admin")
public record AdminProperties(@DefaultValue("https://admin.playchale.com") String url, @DefaultValue("true") boolean alerts) {
}
