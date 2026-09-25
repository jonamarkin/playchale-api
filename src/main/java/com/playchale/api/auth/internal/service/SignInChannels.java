package com.playchale.api.auth.internal.service;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Outside the dev profile, refuses to start unless at least one way of signing in has its provider
 * configured (SMS or email): with neither, nobody could sign in.
 */
@Component
@Profile("!dev")
class SignInChannels implements InitializingBean {

	private final AuthService auth;

	SignInChannels(AuthService auth) {
		this.auth = auth;
	}

	@Override
	public void afterPropertiesSet() {
		var options = auth.options();
		if (!options.phone() && !options.email()) {
			throw new IllegalStateException("No SMS or email provider is configured, so nobody could sign in");
		}
	}

}
