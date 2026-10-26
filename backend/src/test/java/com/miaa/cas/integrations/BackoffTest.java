package com.miaa.cas.integrations;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class BackoffTest {

    @Test
    void doublesFromTheBaseAndIsCapped() {
        TransmissionService s = new TransmissionService(List.of(), null, null, null, null, null, Clock.systemUTC(), 8, Duration.ofMinutes(1),
                Duration.ofHours(1), Duration.ofMinutes(10));
        assertThat(s.backoff(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(s.backoff(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(s.backoff(6)).isEqualTo(Duration.ofMinutes(32));
        assertThat(s.backoff(7)).isEqualTo(Duration.ofHours(1));
        assertThat(s.backoff(40)).isEqualTo(Duration.ofHours(1));
    }
}
