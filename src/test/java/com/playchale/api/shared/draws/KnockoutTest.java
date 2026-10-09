package com.playchale.api.shared.draws;

import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The draw: who plays whom, who sits out the first round, and how the winners meet after that. */
class KnockoutTest {

	/** Teams named by their entry order, so a tie reads like "1 v 8". */
	private static final List<UUID> TEAMS = IntStream.range(0, 32).mapToObj(i -> UUID.nameUUIDFromBytes(("team" + i).getBytes())).toList();

	private static List<UUID> teams(int n) {
		return TEAMS.subList(0, n);
	}

	private static int seed(UUID team) {
		return TEAMS.indexOf(team) + 1;
	}

	private static String read(Knockout.Tie tie) {
		return "%d v %d".formatted(seed(tie.home()), seed(tie.away()));
	}

	@Test
	void theBracketIsTheNextPowerOfTwo() {
		assertThat(Knockout.size(2)).isEqualTo(2);
		assertThat(Knockout.size(5)).isEqualTo(8);
		assertThat(Knockout.size(8)).isEqualTo(8);
		assertThat(Knockout.size(9)).isEqualTo(16);
		assertThat(Knockout.rounds(8)).isEqualTo(3);
		assertThat(Knockout.rounds(5)).isEqualTo(3);
		assertThat(Knockout.shape(8)).containsExactly(4, 2, 1);
		assertThat(Knockout.name(1)).isEqualTo("Final");
		assertThat(Knockout.name(2)).isEqualTo("Semi-finals");
		assertThat(Knockout.name(4)).isEqualTo("Quarter-finals");
		assertThat(Knockout.name(8)).isEqualTo("Round of 16");
	}

	@Test
	void aFullBracketPairsTheFirstWithTheLast() {
		var draw = Knockout.firstRound(teams(8));
		assertThat(draw.byes()).isEmpty();
		assertThat(draw.ties()).extracting(KnockoutTest::read).containsExactly("1 v 8", "4 v 5", "2 v 7", "3 v 6");
		assertThat(draw.ties()).extracting(Knockout.Tie::slot).containsExactly(0, 1, 2, 3);
	}

	@Test
	void teamsThatDontFillTheBracketGetAByeAndStayApart() {
		var draw = Knockout.firstRound(teams(6));
		// Six teams in a bracket of eight: the first two entered sit out the first round.
		assertThat(draw.ties()).extracting(KnockoutTest::read).containsExactly("4 v 5", "3 v 6");
		assertThat(draw.byes().values()).extracting(KnockoutTest::seed).containsExactly(1, 2);
		// And they're on opposite sides, so they can only meet in the final.
		assertThat(draw.byes().keySet()).containsExactly(0, 2);
	}

	@Test
	void theWinnersMeetInOrderUntilOneIsLeft() {
		var draw = Knockout.firstRound(teams(6));
		// The favourites come through: 4 beats 5, 3 beats 6.
		var through = new TreeMap<Integer, UUID>(draw.byes());
		through.put(1, TEAMS.get(3));
		through.put(3, TEAMS.get(2));

		var semis = Knockout.nextRound(2, through);
		assertThat(semis).extracting(KnockoutTest::read).containsExactly("1 v 4", "2 v 3");

		var finalists = new TreeMap<Integer, UUID>();
		finalists.put(0, TEAMS.get(0));
		finalists.put(1, TEAMS.get(1));
		assertThat(Knockout.nextRound(3, finalists)).extracting(KnockoutTest::read).containsExactly("1 v 2");
	}

	@Test
	void twoTeamsAreJustAFinal() {
		var draw = Knockout.firstRound(teams(2));
		assertThat(draw.byes()).isEmpty();
		assertThat(draw.ties()).extracting(KnockoutTest::read).containsExactly("1 v 2");
		assertThat(Knockout.shape(2)).containsExactly(1);
	}

	@Test
	void anOddBracketStillPairsEveryoneOnce() {
		var draw = Knockout.firstRound(teams(11));
		// Eleven of sixteen: five ties, five byes, and nobody left over.
		assertThat(draw.ties()).hasSize(3);
		assertThat(draw.byes()).hasSize(5);
		var playing = draw.ties().stream().flatMap(t -> java.util.stream.Stream.of(t.home(), t.away())).toList();
		assertThat(playing).doesNotHaveDuplicates().hasSize(6);
		assertThat(playing).doesNotContainAnyElementsOf(draw.byes().values());
		assertThat(draw.ties().size() * 2 + draw.byes().size()).isEqualTo(11);
	}

}
