package com.playchale.api.shared.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses request bodies over {@link #MAX_BYTES} before anything reads them. Without it a body is
 * read whole into memory before its size is checked (a logo, say), so a handful of requests a few
 * hundred megabytes each could take the API down. Nothing PlayChale accepts comes close: the largest
 * is a 256 KB logo.
 *
 * <p>A body that says its length up front is refused straight away with 413. One that doesn't
 * (chunked) is counted as it's read, and reading stops with an error past the limit.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class RequestSizeFilter extends OncePerRequestFilter {

	static final long MAX_BYTES = 1024 * 1024;

	private static final String TOO_BIG = """
			{"error":{"code":"invalid","message":"That’s too big to send. Try a smaller file."}}""";

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (request.getContentLengthLong() > MAX_BYTES) {
			response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.setCharacterEncoding("UTF-8");
			response.getWriter().write(TOO_BIG);
			return;
		}
		chain.doFilter(request.getContentLengthLong() < 0 ? new Capped(request) : request, response);
	}

	/** A request whose body is counted as it's read, and cut off past the limit. */
	private static final class Capped extends HttpServletRequestWrapper {

		private ServletInputStream stream;

		Capped(HttpServletRequest request) {
			super(request);
		}

		@Override
		public ServletInputStream getInputStream() throws IOException {
			if (stream == null) {
				stream = new CountingStream(super.getInputStream());
			}
			return stream;
		}

	}

	private static final class CountingStream extends ServletInputStream {

		private final ServletInputStream in;

		private long read;

		CountingStream(ServletInputStream in) {
			this.in = in;
		}

		@Override
		public int read() throws IOException {
			int b = in.read();
			if (b >= 0) {
				count(1);
			}
			return b;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			int n = in.read(buffer, offset, length);
			if (n > 0) {
				count(n);
			}
			return n;
		}

		private void count(int n) throws IOException {
			read += n;
			if (read > MAX_BYTES) {
				throw new IOException("Request body over " + MAX_BYTES + " bytes");
			}
		}

		@Override
		public boolean isFinished() {
			return in.isFinished();
		}

		@Override
		public boolean isReady() {
			return in.isReady();
		}

		@Override
		public void setReadListener(ReadListener listener) {
			in.setReadListener(listener);
		}

	}

}
