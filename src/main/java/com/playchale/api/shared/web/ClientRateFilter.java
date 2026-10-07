package com.playchale.api.shared.web;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps how fast one address can call the API, before any other work is done. Each request to a
 * page like Discover holds a database connection for a couple of dozen queries, so a single script
 * sending a hundred requests a second could slow PlayChale for everyone, well below what Cloudflare
 * treats as an attack. Twenty a second, with bursts of sixty, is far above what a person browsing
 * makes, and leaves room for several people behind one mobile network address.
 *
 * <p>Counted in memory: there is one copy of the API, it costs nothing per request, and a restart
 * forgetting the counts doesn't matter. The address is the visitor's real one (Caddy passes it on;
 * see application.yml). IPv6 addresses count by their /64, the block one home or phone is given, so
 * rotating through it doesn't escape the limit.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
public class ClientRateFilter extends OncePerRequestFilter {

	static final double PER_SECOND = 20;

	static final double BURST = 60;

	/** Addresses tracked at most, so a flood from many addresses can't fill the memory either. */
	private static final int MAX_TRACKED = 200_000;

	private static final String TOO_MANY = """
			{"error":{"code":"conflict","message":"Too many requests at once. Wait a moment and try again."}}""";

	private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		// Health checks come from the server itself and monitoring; this machine is never limited.
		return request.getRequestURI().startsWith("/actuator/health") || isLoopback(request.getRemoteAddr());
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (buckets.size() > MAX_TRACKED) {
			forgetIdle(0);
		}
		var bucket = buckets.computeIfAbsent(key(request.getRemoteAddr()), k -> new Bucket(System.nanoTime()));
		if (!bucket.take(System.nanoTime())) {
			response.setStatus(429);
			response.setHeader("Retry-After", "2");
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.setCharacterEncoding("UTF-8");
			response.getWriter().write(TOO_MANY);
			return;
		}
		chain.doFilter(request, response);
	}

	/** Forgets addresses that have been quiet long enough to be back to a full bucket. */
	@Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
	void forgetQuiet() {
		forgetIdle((long) (BURST / PER_SECOND * 1e9));
	}

	private void forgetIdle(long idleNanos) {
		var now = System.nanoTime();
		buckets.entrySet().removeIf(e -> now - e.getValue().last() >= idleNanos);
	}

	/** The address a limit is kept for: an IPv4 address, or an IPv6 address's /64. */
	static String key(String address) {
		if (address == null || address.indexOf(':') < 0) {
			return address == null ? "" : address;
		}
		try {
			var bytes = InetAddress.getByName(address).getAddress();
			if (bytes.length != 16) {
				return address;
			}
			var key = new StringBuilder("v6:");
			for (int i = 0; i < 8; i++) {
				key.append(String.format("%02x", bytes[i]));
			}
			return key.toString();
		}
		catch (UnknownHostException e) {
			return address;
		}
	}

	private static boolean isLoopback(String address) {
		return "127.0.0.1".equals(address) || "0:0:0:0:0:0:0:1".equals(address) || "::1".equals(address);
	}

	/** A token bucket: fills at {@link #PER_SECOND}, holds at most {@link #BURST}, and each request takes one. */
	static final class Bucket {

		private double tokens = BURST;

		private long last;

		Bucket(long now) {
			this.last = now;
		}

		synchronized boolean take(long now) {
			tokens = Math.min(BURST, tokens + (now - last) / 1e9 * PER_SECOND);
			last = now;
			if (tokens < 1) {
				return false;
			}
			tokens -= 1;
			return true;
		}

		synchronized long last() {
			return last;
		}

	}

}
