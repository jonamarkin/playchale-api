package com.playchale.api.auth.web;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.playchale.api.shared.security.CurrentUser;
import com.playchale.api.shared.security.GuestHostsAllowed;
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

/**
 * Fills in {@link CurrentUser} and {@code Optional<CurrentUser>} controller parameters from the
 * session: the cookie the web app carries, or the bearer token the phone app sends
 * ({@link SessionCookies#tokenOf}), for every module's controllers.
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
		var session = (Optional<AuthService.SessionInfo>) request.getAttribute(ATTRIBUTE);
		if (session == null) {
			session = auth.sessionFor(SessionCookies.tokenOf(request));
			request.setAttribute(ATTRIBUTE, session);
			if (session.isPresent() && !READS.contains(request.getMethod())) {
				withinLimits(session.get().userId());
			}
		}
		// A guest host (hosting without an account) gets only what's marked for them: anywhere else
		// they're someone signed out, or asked to sign up.
		var guestKeptOut = session.map(AuthService.SessionInfo::guest).orElse(false) && !allowsGuests(parameter);
		var me = guestKeptOut ? Optional.<CurrentUser>empty() : session.map(s -> new CurrentUser(s.userId()));
		if (parameter.getParameterType() == Optional.class) {
			return me;
		}
		return me.orElseThrow(() -> BusinessException.unauthenticated(guestKeptOut
				? "Sign up to do that: it takes a minute, and the games you host come with you."
				: "Please sign in to continue."));
	}

	private static boolean allowsGuests(MethodParameter parameter) {
		return parameter.hasMethodAnnotation(GuestHostsAllowed.class) || parameter.getContainingClass().isAnnotationPresent(GuestHostsAllowed.class);
	}

	private void withinLimits(UUID userId) {
		if (!limiter.tryAcquire("writes:" + userId, Duration.ofMinutes(10), WRITES_PER_TEN_MINUTES)
				|| !limiter.tryAcquire("writes-daily:" + userId, Duration.ofDays(1), WRITES_PER_DAY)) {
			throw BusinessException.conflict("You’re doing that too fast. Wait a few minutes, then try again.");
		}
	}

}
