package com.playchale.api.payments.internal.service;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Chases payments that are still pending after the payer's app stopped asking (they closed it, lost
 * signal, approved on their phone an hour later). Every 30 seconds it claims the payments due a
 * check, then asks the provider about each one.
 *
 * <p>Claiming is one statement with FOR UPDATE SKIP LOCKED: it pushes each claimed payment's next
 * check back (1, 2, 4... up to 60 minutes apart) and counts the check, so two copies of the API never
 * check the same payment, and one that crashes mid-check just leaves it for its next turn. After
 * {@link #MAX_CHECKS} checks (about a day) it stops and says so in the log: a payment is never marked
 * failed on a guess, only when the provider says it failed.
 */
@Component
class PaymentChecks {

	static final int MAX_CHECKS = 24;

	private static final int BATCH = 20;

	private static final Logger log = LoggerFactory.getLogger(PaymentChecks.class);

	private final JdbcClient jdbc;

	private final PaymentService payments;

	private final TransactionTemplate tx;

	private final Clock clock;

	PaymentChecks(JdbcClient jdbc, PaymentService payments, TransactionTemplate tx, Clock clock) {
		this.jdbc = jdbc;
		this.payments = payments;
		this.tx = tx;
		this.clock = clock;
	}

	@Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT30S")
	public void checkDuePayments() {
		for (var id : claimDue()) {
			try {
				var stillPending = payments.reconcile(id);
				if (stillPending && checksOf(id) >= MAX_CHECKS) {
					log.warn("Payment {} is still pending after {} checks with the provider. Look it up with them.", id, MAX_CHECKS);
				}
			}
			catch (RuntimeException e) {
				// One payment's trouble (the provider timing out, say) mustn't stop the others; it's tried again later.
				log.warn("Checking payment {} with the provider failed; will try again", id, e);
			}
		}
	}

	/** Claims up to a batch of payments due a check, in its own short transaction. */
	List<UUID> claimDue() {
		var now = clock.instant().atOffset(ZoneOffset.UTC);
		return tx.execute(status -> jdbc.sql("""
				UPDATE payments
				SET checks = checks + 1,
				    next_check_at = :now + least(power(2, checks), 60) * interval '1 minute'
				WHERE id IN (
				    SELECT id FROM payments
				    WHERE status = 'pending' AND next_check_at <= :now AND checks < :max
				    ORDER BY next_check_at
				    LIMIT :batch
				    FOR UPDATE SKIP LOCKED)
				RETURNING id
				""")
			.param("now", now)
			.param("max", MAX_CHECKS)
			.param("batch", BATCH)
			.query(UUID.class)
			.list());
	}

	private int checksOf(UUID id) {
		return jdbc.sql("SELECT checks FROM payments WHERE id = :id").param("id", id).query(Integer.class).single();
	}

}
