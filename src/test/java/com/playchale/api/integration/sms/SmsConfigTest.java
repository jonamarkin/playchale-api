package com.playchale.api.integration.sms;

import com.playchale.api.shared.scheduling.ClusterLock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Which sender runs where: Rancard once its key is set, the log on a laptop without one, and none at
 * all elsewhere (so signing in by phone isn't offered). Nothing outside this package names the
 * provider: they ask for an {@link SmsSender}.
 */
class SmsConfigTest {

	private final ApplicationContextRunner context = new ApplicationContextRunner()
		.withUserConfiguration(SmsConfig.class)
		.withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
		.withBean(ClusterLock.class, () -> mock(ClusterLock.class));

	@Test
	void rancardSendsOnceItsKeyIsSet() {
		context.withPropertyValues("playchale.rancard.api-key=not-a-real-key", "playchale.rancard.sender-id=PlayChale").run(app -> {
			assertThat(app).getBean(SmsSender.class).isInstanceOf(RancardSmsSender.class);
			assertThat(app).hasSingleBean(SmsBalance.class);
			assertThat(app).hasSingleBean(SmsBalanceWatch.class);
		});
	}

	@Test
	void aLaptopWithoutAKeyLogsTexts() {
		context.withPropertyValues("spring.profiles.active=dev").run(app -> {
			assertThat(app).hasSingleBean(SmsSender.class);
			assertThat(app.getBean(SmsSender.class)).isNotInstanceOf(RancardSmsSender.class);
			assertThat(app).doesNotHaveBean(SmsBalance.class);
		});
	}

	@Test
	void anywhereElseWithoutAKeyThereIsNoSender() {
		// The test run itself is on the dev profile (see pom.xml), so production is said out loud.
		context.withPropertyValues("spring.profiles.active=production").run(app -> {
			assertThat(app).doesNotHaveBean(SmsSender.class);
			assertThat(app).doesNotHaveBean(SmsBalanceWatch.class);
		});
	}

}
