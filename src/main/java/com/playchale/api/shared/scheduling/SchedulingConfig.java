package com.playchale.api.shared.scheduling;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on {@code @Scheduled} jobs (clean-ups, payment checks). Each job guards itself with a {@link ClusterLock}. */
@Configuration
@EnableScheduling
class SchedulingConfig {
}
