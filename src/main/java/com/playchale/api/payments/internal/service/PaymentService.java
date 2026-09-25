package com.playchale.api.payments.internal.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
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
@EnableConfigurationProperties(PaymentSettings.class)
public class PaymentService {

	private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

	/** A statement shows the latest this many lines. */
	private static final int STATEMENT_LIMIT = 200;

	private final PaymentRepository payments;

	private final MovementRepository movements;

	private final GameShares games;

	private final UserDirectory users;

	private final PaymentProvider provider;

	private final ApplicationEventPublisher events;

	private final PaymentSettings settings;

	private final JdbcClient jdbc;

	private final Clock clock;

	PaymentService(PaymentRepository payments, MovementRepository movements, GameShares games, UserDirectory users,
			PaymentProvider provider, ApplicationEventPublisher events, PaymentSettings settings, JdbcClient jdbc, Clock clock) {
		this.payments = payments;
		this.movements = movements;
		this.games = games;
		this.users = users;
		this.provider = provider;
		this.events = events;
		this.settings = settings;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	/**
	 * payments.start: starts collecting the player's share. It stays pending until they approve it on
	 * their phone, or pay on the provider's checkout page (its link comes back as authorizationUrl).
	 */
	@Transactional
	public PaymentResponse start(UUID gameId, String method, String payerPhone, UUID me) {
		var share = games.shareDue(gameId, me);
		var email = users.find(me).map(u -> u.email()).orElse(null);
		if (provider.needsEmail() && email == null) {
			throw BusinessException.invalid("Add your email address first. Paystack sends your receipt there.");
		}
		String phone = null;
		if (!Payment.CARD.equals(method) && provider.needsPayerPhone()) {
			phone = payerPhone == null ? null : Market.get(Market.DEFAULT).normalisePhone(payerPhone)
				.orElseThrow(() -> BusinessException.invalid("Enter the mobile money number to charge."));
		}
		var payment = payments.save(new Payment(gameId, me, method, share.amount(), share.currency(), phone, provider.needsPayerPhone(),
				clock.instant()));
		var started = provider.charge(new PaymentProvider.Charge(payment.getReference(), payment.getMethod(), payment.getAmount(),
				payment.getCurrency(), payment.getPayerPhone(), email, returnUrl(payment)));
		if (started.checkoutUrl() != null) {
			payment.sendToCheckout(started.checkoutUrl());
		}
		return PaymentResponse.of(payment);
	}

	/**
	 * A payment provider's webhook. Handled once per delivery however often it's retried, and only
	 * ever as a prompt to ask the provider about the payment. Returns false when it isn't genuine.
	 */
	@Transactional
	public boolean handleWebhook(String providerName, String body, String signature) {
		var reference = provider.webhookReference(body, signature);
		if (reference.isEmpty()) {
			return false;
		}
		int fresh = jdbc.sql("INSERT INTO webhook_events (provider, body_hash, event, reference, received_at) "
				+ "VALUES (:provider, encode(sha256(convert_to(:body, 'UTF8')), 'hex'), 'charge', :reference, :now) ON CONFLICT DO NOTHING")
			.param("provider", providerName).param("body", body).param("reference", reference.get())
			.param("now", clock.instant().atOffset(ZoneOffset.UTC))
			.update();
		if (fresh == 1) {
			payments.findByReference(reference.get()).ifPresent(payment -> reconcile(payment.getId()));
		}
		return true;
	}

	/** Where a hosted checkout sends the payer back to: the game, which picks the payment up again. */
	private String returnUrl(Payment payment) {
		var base = settings.webAppUrl();
		if (base == null || base.isBlank()) {
			return null;
		}
		return "%s/games/%s?payment=%s".formatted(base.replaceAll("/+$", ""), payment.getGameId(), payment.getId());
	}

	/**
	 * payments.status: where a payment has got to, asking the provider while it's pending. The moment
	 * it succeeds, the share is marked paid, both sides' statements get their line and the host is told.
	 */
	@Transactional
	public PaymentResponse status(UUID paymentId, UUID me) {
		var payment = payments.lockById(paymentId).filter(p -> p.belongsTo(me)).orElseThrow(PaymentService::notFound);
		refresh(payment);
		return PaymentResponse.of(payment);
	}

	/**
	 * Asks the provider about a payment the payer's app has stopped polling: from the background
	 * worker, or a provider's webhook. Returns whether it's still pending.
	 */
	@Transactional
	public boolean reconcile(UUID paymentId) {
		return payments.lockById(paymentId).map(payment -> {
			refresh(payment);
			return payment.isPending();
		}).orElse(false);
	}

	/** While a payment is pending, asks the provider where it's got to, and settles it the moment it has. */
	private void refresh(Payment payment) {
		if (!payment.isPending()) {
			return;
		}
		var now = clock.instant();
		PaymentProvider.Status status;
		try {
			status = provider.check(charge(payment), payment.getCreatedAt());
		}
		catch (RuntimeException e) {
			// The provider couldn't be asked just now. The payment stays pending and is asked again later.
			log.warn("Couldn't check payment {} with the provider", payment.getId(), e);
			return;
		}
		switch (status.state()) {
			case PENDING -> {
			}
			case FAILED -> payment.fail(status.failureReason(), now);
			case SUCCEEDED -> settle(payment, now);
		}
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
		return new PaymentProvider.Charge(p.getReference(), p.getMethod(), p.getAmount(), p.getCurrency(), p.getPayerPhone(), null, null);
	}

	private static BusinessException notFound() {
		return BusinessException.notFound("We couldn’t find that payment.");
	}

}
