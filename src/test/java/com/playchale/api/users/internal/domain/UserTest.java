package com.playchale.api.users.internal.domain;

import java.util.List;

import com.playchale.api.shared.error.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The rules for a player's profile. A plain unit test: no Spring, no database. */
class UserTest {

	private final User user = new User("+233244555123", "GH", User.TINTS.getFirst());

	@Test
	void aNewPlayerStartsBlankUntilOnboarding() {
		assertThat(user.getName()).isEmpty();
		assertThat(user.getHandle()).isEmpty();
		assertThat(user.getSports()).isEmpty();
		assertThat(user.isOnboarded()).isFalse();
	}

	@Test
	void handlesAreStoredLowerCaseWithoutTheAt() {
		user.changeHandle("  @Kwame_10 ");
		assertThat(user.getHandle()).isEqualTo("kwame_10");
	}

	@Test
	void handlesMustBeThreeToTwentyLettersDigitsOrUnderscores() {
		for (var bad : List.of("ab", "kwame mensah", "kwame-m", "a".repeat(21), "")) {
			assertThatThrownBy(() -> user.changeHandle(bad)).as(bad).isInstanceOf(BusinessException.class)
				.hasMessage("Use 3 to 20 characters: letters, numbers or _.");
		}
	}

	@Test
	void namesAreTrimmedAndRequired() {
		user.rename("  Kwame Mensah ");
		assertThat(user.getName()).isEqualTo("Kwame Mensah");
		assertThatThrownBy(() -> user.rename("   ")).hasMessage("Tell us your name.");
	}

	@Test
	void onlyKnownSportsEachOnce() {
		user.playSports(List.of("football", "tennis", "football"));
		assertThat(user.getSports()).containsExactly("football", "tennis");
		assertThatThrownBy(() -> user.playSports(List.of("cricket"))).hasMessage("Pick sports from the list.");
	}

	@Test
	void blankOptionalFieldsClearThem() {
		user.moveTo("East Legon");
		user.playPosition("Striker");
		user.moveTo(" ");
		user.playPosition("");
		assertThat(user.getArea()).isNull();
		assertThat(user.getPosition()).isNull();
	}

	@Test
	void payoutNumbersAreStoredInFullForTheirMarket() {
		user.payTo("020 123 4567");
		assertThat(user.getPayoutPhone()).isEqualTo("+233201234567");
		assertThatThrownBy(() -> user.payTo("12345")).isInstanceOf(BusinessException.class);
		user.payTo("");
		assertThat(user.getPayoutPhone()).isNull();
	}

	@Test
	void onboardingNeedsANameAndAHandle() {
		assertThatThrownBy(user::finishOnboarding).hasMessage("Add your name and a username to finish.");
		user.rename("Kwame");
		user.changeHandle("kwame");
		user.finishOnboarding();
		assertThat(user.isOnboarded()).isTrue();
	}

	@Test
	void emailsAreStoredLowerCaseAndChecked() {
		user.emailTo(" Kwame@Example.COM ");
		assertThat(user.getEmail()).isEqualTo("kwame@example.com");
		assertThatThrownBy(() -> user.emailTo("kwame at example")).hasMessage("Enter an email address like name@example.com.");
		user.emailTo("");
		assertThat(user.getEmail()).isNull();
	}

}
