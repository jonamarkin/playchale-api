package com.playchale.api.notifications.internal.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Each delivery on its own virtual thread: they spend their time waiting on the network. */
@Component
class VirtualThreadPushQueue implements PushQueue {

	private static final Logger log = LoggerFactory.getLogger("push");

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	@Override
	public void submit(Runnable delivery) {
		executor.execute(() -> {
			try {
				delivery.run();
			}
			catch (RuntimeException e) {
				log.warn("Delivering a notification failed", e);
			}
		});
	}

	@PreDestroy
	void close() {
		executor.close();
	}

}
