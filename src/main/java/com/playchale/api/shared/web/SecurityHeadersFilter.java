package com.playchale.api.shared.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Security headers on every response. The API only answers with JSON, so nothing in a response may
 * run, load or be framed, and browsers mustn't guess at content types. HSTS keeps browsers on HTTPS
 * once they've seen it there (Caddy terminates TLS in front of the API).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class SecurityHeadersFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
		response.setHeader("X-Content-Type-Options", "nosniff");
		response.setHeader("X-Frame-Options", "DENY");
		response.setHeader("Referrer-Policy", "no-referrer");
		response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
		chain.doFilter(request, response);
	}

}
