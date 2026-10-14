package com.playchale.api.shared.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An endpoint a guest host may use: someone hosting a game without an account. Everything else
 * treats a guest host as signed out (where signing in is optional) or asks them to sign up (where
 * it's needed), so a new endpoint is closed to guests until it's marked here on purpose
 * (CurrentUserArgumentResolver; GuestHostEndpointsTest lists every one).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.METHOD, ElementType.TYPE })
public @interface GuestHostsAllowed {
}
