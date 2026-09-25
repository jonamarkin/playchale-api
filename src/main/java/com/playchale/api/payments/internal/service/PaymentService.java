package com.playchale.api.payments.internal.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.playchale.api.games.api.GameEvents;
import com.playchale.api.games.api.GameShares;
import com.playchale.api.integration.payments.PaymentProvider;
import com.playchale.api.market.Market;
import com.playchale.api.payments.api.PaymentReceived;
import com.playchale.api.payments.internal.domain.Movement;
import com.playchale.api.payments.internal.domain.Payment;
import com.playchale.api.payments.internal.repository.MovementRepository;
import com.playchale.api.payments.internal.repository.PaymentRepository;
import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.users.api.UserDirectory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Collecting players' shares through the payment provider, and the ledger of money that moved.
 * PlayChale never holds the money: the provider moves it from the payer to the host, and this
 * records that it happened.
 */
@Service
public class PaymentService {

	/** A statement shows the latest this many lines. */
	private static final int STATEMENT_LIMIT = 200;

	private final PaymentRepository payments;

	private final MovementRepository movements;

	private final GameShares games;

	private final UserDirectory users;

	private final PaymentProvider provider;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	PaymentService(PaymentRepository payments, MovementRepository movements, GameShares games, UserDirectory users,
			PaymentProvider provider, ApplicationEventPublisher events, Clock clock) {
		this.payments = payments;
		this.movements = movements;
		this.games = games;
		this.users = users;
		this.provider = provider;
		this.events = events;
		this.clock = clock;
	}

	/** payments.start: starts collecting the player's share. It stays pending until they approve it. */
	@Transactional
	public PaymentResponse start(UUID gameId, String method, String payerPhone, UUID me) {
		var share = games.shareDue(gameId, me);
		String phone = null;
		if (!Payment.CARD.equals(method)) {
			phone = payerPhone == null ? null : Market.get(Market.DEFAULT).normalisePhone(payerPhone)
				.orElseThrow(() -> BusinessException.invalid("Enter the mobile money number to charge."));
		}
		var payment = payments.save(new Payment(gameId, me, method, share.amount(), share.currency(), phone, clock.instant()));
		provider.charge(charge(payment));
		return PaymentResponse.of(payment);
	}

	/**
	 * payments.status: where a payment has got to, asking the provider while it's pending. The moment
	 * it succeeds, the share is marked paid, both sides' statements get their line and the host is told.
	 */
	@Transactional
	public PaymentResponse status(UUID paymentId, UUID me) {
		var payment = payments.lockById(paymentId).filter(p -> p.belongsTo(me)).orElseThrow(PaymentService::notFound);
		if (!payment.isPending()) {
			return PaymentResponse.of(payment);
		}
		var now = clock.instant();
		var status = provider.check(charge(payment), payment.getCreatedAt());
		switch (status.state()) {
			case PENDING -> {
			}
			case FAILED -> payment.fail(status.failureReason(), now);
			case SUCCEEDED -> settle(payment, now);
		}
		return PaymentResponse.of(payment);
	}

	private void settle(Payment payment, Instant now) {
		payment.succeed(now);
		var paid = games.markPaidInApp(payment.getGameId(), payment.getUserId(), payment.getId());
		movements.save(Movement.shareOut(payment.getUserId(), paid.hostId(), payment.getGameId(), payment.getAmount(), payment.getCurrency(),
				payment.getMethod(), payment.getReference(), payment.getId(), now));
		if (!paid.hostId().equals(payment.getUserId())) {
			movements.save(Movement.shareIn(paid.hostId(), payment.getUserId(), payment.getGameId(), payment.getAmount(),
					payment.getCurrency(), payment.getMethod(), payment.getReference(), payment.getId(), now));
			events.publishEvent(new PaymentReceived(payment.getGameId(), paid.title(), paid.hostId(), payment.getUserId(),
					payment.getAmount(), payment.getCurrency(), paid.paid(), paid.players()));
		}
	}

	/** payments.get: the player's own payments only. */
	@Transactional(readOnly = true)
	public Optional<PaymentResponse> get(UUID paymentId, UUID me) {
		return payments.findById(paymentId).filter(p -> p.belongsTo(me)).map(PaymentResponse::of);
	}

	/** payments.statement: every movement involving the player, newest first. There is no balance. */
	@Transactional(readOnly = true)
	public List<MovementResponse> statement(UUID me) {
		var lines = movements.findByUserIdOrderByCreatedAtDescIdDesc(me, Limit.of(STATEMENT_LIMIT));
		var people = users.findAll(lines.stream().map(Movement::getCounterpartyId).filter(Objects::nonNull).distinct().toList());
		var titles = games.titles(lines.stream().map(Movement::getGameId).filter(Objects::nonNull).distinct().toList());
		return lines.stream()
			.map(m -> new MovementResponse(m.getId(), m.getUserId(), m.getDirection(), m.getKind(), m.getAmount(), m.getCurrency(),
					m.getGameId(), m.getCounterpartyId(), m.getMethod(), m.getReference(), m.getStatus(), m.getCreatedAt(),
					m.getCounterpartyId() == null || !people.containsKey(m.getCounterpartyId()) ? null : people.get(m.getCounterpartyId()).toPublic(),
					m.getGameId() == null ? null : titles.get(m.getGameId())))
			.toList();
	}

	/**
	 * A share handed to the host in cash: both statements get their line, in the same transaction as
	 * the host marking it. A guest has no account, so only the host's side is recorded for them.
	 */
	@EventListener
	void on(GameEvents.CashShareCollected e) {
		if (e.amount() <= 0) {
			return;
		}
		var now = clock.instant();
		var game = e.game();
		if (e.payerId() != null) {
			movements.save(Movement.shareOut(e.payerId(), game.hostId(), game.gameId(), e.amount(), e.currency(), "cash", null, null, now));
		}
		if (!game.hostId().equals(e.payerId())) {
			movements.save(Movement.shareIn(game.hostId(), e.payerId(), game.gameId(), e.amount(), e.currency(), "cash", null, null, now));
		}
	}

	private static PaymentProvider.Charge charge(Payment p) {
		return new PaymentProvider.Charge(p.getReference(), p.getMethod(), p.getAmount(), p.getCurrency(), p.getPayerPhone());
	}

	private static BusinessException notFound() {
		return BusinessException.notFound("We couldn’t find that payment.");
	}

}
