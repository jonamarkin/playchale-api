package com.playchale.api.games.internal.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.playchale.api.market.Market;
import com.playchale.api.shared.error.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The rules of a game's roster. A plain unit test: no Spring, no database. */
class GameTest {

	private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");

	private static final Instant KICKOFF = NOW.plus(Duration.ofDays(1));

	private final UUID host = UUID.randomUUID();

	private final UUID kojo = UUID.randomUUID();

	private final UUID ama = UUID.randomUUID();

	static GameDetails details(int capacity, long totalCost) {
		return new GameDetails("football", "5-a-side", " ", KICKOFF, 60, "unlisted", null, null, "Legon Park", null, null, capacity,
				totalCost, null, "public", null);
	}

	private Game game(int capacity, long totalCost) {
		var game = new Game(details(capacity, totalCost), host, Market.get("GH"), NOW);
		game.playAt("Legon Park", null, null);
		return game;
	}

	@Test
	void theHostTakesTheFirstSpotAndAnEmptyTitleIsFilledIn() {
		var game = game(10, 25_000);
		assertThat(game.getTitle()).isEqualTo("5-a-side football");
		assertThat(game.getParticipants()).singleElement().satisfies(p -> {
			assertThat(p.getUserId()).isEqualTo(host);
			assertThat(p.isPaid()).as("the host pays their share like everyone else").isFalse();
		});
		assertThat(game.getStatus()).isEqualTo(Game.OPEN);
		assertThat(game(10, 0).getParticipants().getFirst().isPaid()).as("nothing to pay in a free game").isTrue();
	}

	@Test
	void newGamesAreChecked() {
		assertThatThrownBy(() -> new Game(details(1, 0), host, Market.get("GH"), NOW)).hasMessage("A game needs at least 2 spots.");
		var past = new GameDetails("football", "5-a-side", null, NOW.minusSeconds(60), 60, "unlisted", null, null, "Legon", null, null, 10, 0, null,
				"public", null);
		assertThatThrownBy(() -> new Game(past, host, Market.get("GH"), NOW)).hasMessage("Pick a time in the future.");
		var wrongFormat = new GameDetails("football", "Doubles", null, KICKOFF, 60, "unlisted", null, null, "Legon", null, null, 10, 0, null,
				"public", null);
		assertThatThrownBy(() -> new Game(wrongFormat, host, Market.get("GH"), NOW)).hasMessage("Pick a sport and format.");
		assertThatThrownBy(() -> game(10, 0).playAt(" ", null, null)).hasMessage("Say where you’re playing.");
	}

	@Test
	void theLastSpotFillsTheGame() {
		var game = game(2, 0);
		game.join(kojo, NOW);
		assertThat(game.getStatus()).isEqualTo(Game.FULL);
		assertThatThrownBy(() -> game.join(ama, NOW)).hasMessage("Sorry, this game just filled up.");
		game.leave(kojo, NOW);
		assertThat(game.getStatus()).isEqualTo(Game.OPEN);
	}

	@Test
	void droppingOutIsWrittenDownWithTheNoticeItGave() {
		var game = game(10, 0);
		game.join(kojo, NOW);
		// Kick-off is a day away, and they pull out three hours before it.
		var threeHoursBefore = KICKOFF.minus(Duration.ofHours(3));
		game.leave(kojo, threeHoursBefore);

		assertThat(game.spotOf(kojo)).as("the spot is given up").isEmpty();
		assertThat(game.getDepartures()).singleElement().satisfies(d -> {
			assertThat(d.getUserId()).isEqualTo(kojo);
			assertThat(d.getNoticeMinutes()).isEqualTo(180);
			assertThat(d.isOwnDoing()).isTrue();
		});
	}

	@Test
	void leavingAfterKickOffIsNoticeInTheOtherDirection() {
		var game = game(10, 0);
		game.join(kojo, NOW);
		game.leave(kojo, KICKOFF.plus(Duration.ofMinutes(20)));
		assertThat(game.getDepartures()).singleElement()
			.satisfies(d -> assertThat(d.getNoticeMinutes()).as("negative once kick-off has passed").isEqualTo(-20));
	}

	@Test
	void aSpotTheHostTookBackIsNotTheirDoing() {
		var game = game(10, 0);
		game.join(kojo, NOW);
		game.remove(game.spotOf(kojo).orElseThrow(), host, "Kojo", NOW);
		assertThat(game.getDepartures()).singleElement()
			.satisfies(d -> assertThat(d.isOwnDoing()).as("the host removed them, so it is not held against them").isFalse());
	}

	@Test
	void aGuestSpotLeavesNoRecord() {
		var game = game(10, 0);
		var guest = game.holdForGuest("Ama", null, "hash", host, NOW);
		game.remove(guest, host, "Ama", NOW);
		assertThat(game.getDepartures()).as("there is no account to hold it against").isEmpty();
	}

	@Test
	void closingAnAccountIsNotADropOut() {
		var game = game(10, 0);
		game.join(kojo, NOW);
		game.giveUpSpotOnAccountClosed(kojo);
		assertThat(game.spotOf(kojo)).as("the spot still goes").isEmpty();
		assertThat(game.getDepartures()).as("but nothing is recorded against someone leaving PlayChale").isEmpty();
	}

	@Test
	void aDropOutNeverHoldsASpot() {
		var game = game(2, 0);
		game.join(kojo, NOW);
		assertThat(game.getStatus()).isEqualTo(Game.FULL);
		game.leave(kojo, NOW);
		assertThat(game.getStatus()).as("the record must not keep the game full").isEqualTo(Game.OPEN);
		game.join(ama, NOW);
		assertThat(game.getParticipants()).hasSize(2);
		assertThat(game.getDepartures()).hasSize(1);
	}

	@Test
	void nobodyJoinsAGameThatsOffOrUnderway() {
		var game = game(10, 0);
		game.cancel(null, List.of(), NOW);
		assertThatThrownBy(() -> game.join(kojo, NOW)).hasMessage("This game was called off by the host.");
		assertThatThrownBy(() -> game(10, 0).join(kojo, KICKOFF)).hasMessage("This game has already been played.");
	}

	@Test
	void payingLocksYouInUntilARefund() {
		var game = game(10, 25_000);
		game.join(kojo, NOW);
		var spot = game.spotOf(kojo).orElseThrow();
		game.paidInCash(spot);
		assertThatThrownBy(() -> game.leave(kojo, NOW)).hasMessage("You’ve already paid. Ask the host to sort out a refund.");
		assertThatThrownBy(() -> game.remove(spot, host, "Kojo", NOW))
			.hasMessage("Kojo has already paid. Sort out a refund with them first, then they can leave.");
		assertThatThrownBy(() -> game.cancel(null, List.of("Kojo"), NOW))
			.hasMessage("Kojo has already paid. Refunds aren’t in the app yet, so sort that out with them first.");
		assertThatThrownBy(() -> game.leave(host, NOW)).hasMessage("Hosts can’t leave their own game.");
	}

	@Test
	void aGuestSpotIsHeldUntilTheRightPersonClaimsIt() {
		var game = game(10, 0);
		var spot = game.holdForGuest("Kofi from work", "+233201234567", "hash", host, NOW);
		assertThat(spot.isGuest()).isTrue();
		assertThat(game.spotsLeft()).isEqualTo(8);

		assertThatThrownBy(() -> game.claim(spot, kojo, "+233244555124"))
			.hasMessage("This invite was sent to a different number. Ask the host to add yours.");
		game.claim(spot, kojo, "+233201234567");
		assertThat(spot.isGuest()).isFalse();
		assertThat(spot.getUserId()).isEqualTo(kojo);
		assertThat(spot.getGuestPhone()).as("the number is forgotten once used").isNull();
	}

	@Test
	void remindersAreForPlayersWhoOweTheirShare() {
		var free = game(10, 0);
		assertThatThrownBy(() -> free.unpaid(null)).isInstanceOf(BusinessException.class).hasMessage("This game is free. There’s nothing to pay.");

		var game = game(10, 25_000);
		game.join(kojo, NOW);
		game.join(ama, NOW);
		game.paidInCash(game.spotOf(ama).orElseThrow());
		game.holdForGuest("Kofi", null, "hash", host, NOW);
		assertThat(game.unpaid(null)).extracting(Participant::getUserId).containsExactly(kojo);
	}

}
