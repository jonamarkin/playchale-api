package com.playchale.api.auth.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How many sign-in texts may go out, beyond the five an hour any one number can get. Texts cost
 * money, and "SMS pumping" (bots requesting texts to premium numbers) is a known way to run up the
 * bill, so these cap it per connection and in total.
 *
 * @param perConnectionPerHour codes one IP address may ask for in an hour (a household or office
 *                             shares one, so not too low)
 * @param perDay               codes the whole service sends in a day before it stops and someone looks
 */
@ConfigurationProperties("playchale.sign-in")
public record SignInLimits(@DefaultValue("20") int perConnectionPerHour, @DefaultValue("3000") int perDay) {
}
