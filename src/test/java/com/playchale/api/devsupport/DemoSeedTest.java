package com.playchale.api.devsupport;

import java.util.Optional;

import com.playchale.api.TestcontainersConfiguration;
import com.playchale.api.competitions.internal.service.CompetitionService;
import com.playchale.api.devsupport.internal.service.SeedIds;
import com.playchale.api.devsupport.internal.service.TestDataService;
import com.playchale.api.games.internal.service.GameService;
import com.playchale.api.profiles.internal.service.PlayerProfileService;
import com.playchale.api.venues.internal.service.VenueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/** The demo data loads into the real schema, with the IDs and state the web app's tests expect. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DemoSeedTest {

	@Autowired
	TestDataService testData;

	@Autowired
	GameService games;

	@Autowired
	VenueService venues;

	@Autowired
	CompetitionService competitions;

	@Autowired
	PlayerProfileService profiles;

	@Test
	void theDemoDataLoadsWithTheMocksIds() {
		testData.reset();
		var kwame = SeedIds.of("u-kwame");

		assertThat(testData.demoAccounts()).hasSize(5).first().satisfies(a -> {
			assertThat(a.user().name()).isEqualTo("Kwame Asante");
			assertThat(a.user().phone()).isEqualTo("+233240000001");
		});

		var saturday = games.get(SeedIds.of("g-osu-sat"), kwame);
		assertThat(saturday.venue().pitchName()).isEqualTo("Pitch A");
		assertThat(saturday.players()).hasSize(7).first().satisfies(p -> assertThat(p.name()).isEqualTo("Kojo Mensah"));
		assertThat(saturday.players().stream().filter(p -> !p.paid())).hasSize(2);

		var monday = games.get(SeedIds.of("g-osu-mon"), kwame);
		assertThat(monday.result()).as("the one to record").isNull();

		var league = competitions.get(SeedIds.of("c-osu-estate"), null).orElseThrow();
		assertThat(league.fixtures()).hasSize(4);
		assertThat(league.table().getFirst().team().name()).isEqualTo("Osu Ballers");
		assertThat(league.table().getFirst().points()).isEqualTo(3);

		assertThat(venues.get(SeedIds.of("v-osu")).orElseThrow().pitches()).hasSize(3);
		var profile = profiles.get(kwame, Optional.of(kwame));
		assertThat(profile.stats().goals()).as("3 in Friday 5s, 2 in the league").isEqualTo(5);
		assertThat(profile.teams()).containsExactly("Osu Ballers");

		testData.reset();
		assertThat(games.get(SeedIds.of("g-osu-sat"), kwame).players()).as("resetting gives the same data again").hasSize(7);
	}

}
