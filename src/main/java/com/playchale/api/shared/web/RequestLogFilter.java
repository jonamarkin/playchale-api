package com.playchale.api.shared.web;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an ID, echoed back in X-Request-Id and attached to every log line written
 * while handling it, so a user's bug report can be matched to the server's logs. Then logs one line
 * per request: method, path, status and how long it took.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLogFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Request-Id";

	private static final Logger log = LoggerFactory.getLogger("request");

	private static final SecureRandom random = new SecureRandom();

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		var id = request.getHeader(HEADER);
		if (id == null || id.isBlank() || id.length() > 64) {
			var bytes = new byte[8];
			random.nextBytes(bytes);
			id = HexFormat.of().formatHex(bytes);
		}
		response.setHeader(HEADER, id);

		// MDC is a per-thread map the logger reads, so every line logged during this request carries the ID.
		MDC.put("request_id", id);
		long start = System.nanoTime();
		try {
			chain.doFilter(request, response);
		}
		finally {
			log.info("{} {} {} {}ms", request.getMethod(), request.getRequestURI(), response.getStatus(),
					(System.nanoTime() - start) / 1_000_000);
			MDC.remove("request_id");
		}
	}

}
