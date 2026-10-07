package com.playchale.api.auth.web;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.playchale.api.shared.security.CurrentUser;
import com.playchale.api.shared.security.RateLimiter;
import com.playchale.api.auth.internal.service.AuthService;
import com.playchale.api.shared.error.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.WebUtils;

/**
 * Fills in {@link CurrentUser} and {@code Optional<CurrentUser>} controller parameters from the
 * session cookie, for every module's controllers.
 *
 * <p>It also limits how fast one account can change things. An account is free to make, so without
 * this a script could create games, invite and add people, and post messages as fast as the server
 * answers. The limits are far above anything a person does by hand.
 */
@Configuration
class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver, WebMvcConfigurer {

	/** The answer is kept on the request, so asking twice in one request doesn't look it up twice. */
	private static final String ATTRIBUTE = CurrentUserArgumentResolver.class.getName();

	/** Changes one account can make in ten minutes, and in a day. */
	static final int WRITES_PER_TEN_MINUTES = 300;

	static final int WRITES_PER_DAY = 3000;

	private static final Set<String> READS = Set.of("GET", "HEAD", "OPTIONS");

	private final AuthService auth;

	private final RateLimiter limiter;

	CurrentUserArgumentResolver(AuthService auth, RateLimiter limiter) {
		this.auth = auth;
		this.limiter = limiter;
	}

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(this);
	}

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		var type = parameter.getParameterType();
		return type == CurrentUser.class
				|| (type == Optional.class && ResolvableType.forMethodParameter(parameter).getGeneric(0).resolve() == CurrentUser.class);
	}

	@Override
	public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest webRequest,
			WebDataBinderFactory binderFactory) {
		var request = webRequest.getNativeRequest(HttpServletRequest.class);
		@SuppressWarnings("unchecked")
		var me = (Optional<CurrentUser>) request.getAttribute(ATTRIBUTE);
		if (me == null) {
			var cookie = WebUtils.getCookie(request, SessionCookies.NAME);
			me = auth.userIdFor(cookie == null ? null : cookie.getValue()).map(CurrentUser::new);
			request.setAttribute(ATTRIBUTE, me);
			if (me.isPresent() && !READS.contains(request.getMethod())) {
				withinLimits(me.get().id());
			}
		}
		if (parameter.getParameterType() == Optional.class) {
			return me;
		}
		return me.orElseThrow(() -> BusinessException.unauthenticated("Please sign in to continue."));
	}

	private void withinLimits(UUID userId) {
		if (!limiter.tryAcquire("writes:" + userId, Duration.ofMinutes(10), WRITES_PER_TEN_MINUTES)
				|| !limiter.tryAcquire("writes-daily:" + userId, Duration.ofDays(1), WRITES_PER_DAY)) {
			throw BusinessException.conflict("You’re doing that too fast. Wait a few minutes, then try again.");
		}
	}

}
