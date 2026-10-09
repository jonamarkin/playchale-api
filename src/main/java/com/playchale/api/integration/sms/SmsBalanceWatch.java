package com.playchale.api.integration.sms;

import com.playchale.api.shared.scheduling.ClusterLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

/**
 * Warns in the log when the SMS bundle runs low, hourly, so it's topped up before sign-in codes stop
 * going out. On one copy of the API at a time.
 */
class SmsBalanceWatch {

	private static final Logger log = LoggerFactory.getLogger(SmsBalanceWatch.class);

	private final SmsBalance balance;

	private final ClusterLock lock;

	SmsBalanceWatch(SmsBalance balance, ClusterLock lock) {
		this.balance = balance;
		this.lock = lock;
	}

	@Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT1H")
	@Transactional
	public void check() {
		if (!lock.tryLock("sms-balance-watch")) {
			return;
		}
		balance.credits().ifPresent(credits -> {
			if (credits < balance.lowAt()) {
				log.warn("The SMS bundle is running low: {} credits left. Top it up with the SMS provider before sign-in codes stop.", credits);
			}
		});
	}

}
