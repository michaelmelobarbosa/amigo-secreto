package br.com.michael.amigosecreto.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.michael.amigosecreto.email.EmailProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EmailQuotaGuardTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-25T15:00:00Z"));
    private final NotificationRepository repository = mock(NotificationRepository.class);

    @Test
    void enforcesGlobalAndPerEventDailyLimitsAcrossOutboxAndDirectSends() {
        EmailProperties properties = new EmailProperties(
                true,
                "no-reply@example.test",
                "Amigo Secreto",
                null,
                new EmailProperties.Brevo("key", true),
                5,
                3);
        EmailQuotaGuard guard = new EmailQuotaGuard(properties, repository, clock);
        UUID eventA = UUID.randomUUID();
        UUID eventB = UUID.randomUUID();

        when(repository.countByStatusAndSentAtGreaterThanEqual(
                eq(NotificationStatus.SENT), any()))
                .thenReturn(2L);
        when(repository.countByParticipant_Event_IdAndStatusAndSentAtGreaterThanEqual(
                eq(eventA), eq(NotificationStatus.SENT), any()))
                .thenReturn(2L);
        when(repository.countByParticipant_Event_IdAndStatusAndSentAtGreaterThanEqual(
                eq(eventB), eq(NotificationStatus.SENT), any()))
                .thenReturn(0L);

        assertThat(guard.allowSend(eventA)).isTrue();
        guard.recordDirectSend(eventA);

        // eventA atingiu 3 (2 outbox + 1 direto), mas o global ainda é 3/5, então eventB pode.
        assertThat(guard.allowSend(eventA)).isFalse();
        assertThat(guard.allowSend(eventB)).isTrue();

        guard.recordDirectSend(eventB);
        guard.recordDirectSend(eventB);
        // Global chegou a 5/5 (2 outbox + 3 diretos).
        assertThat(guard.allowSend(eventB)).isFalse();
    }

    @Test
    void resetsDirectCountersWhenUtcDayRollsOver() {
        EmailProperties properties = new EmailProperties(
                true,
                "no-reply@example.test",
                "Amigo Secreto",
                null,
                new EmailProperties.Brevo("key", true),
                1,
                1);
        EmailQuotaGuard guard = new EmailQuotaGuard(properties, repository, clock);
        UUID eventId = UUID.randomUUID();

        when(repository.countByStatusAndSentAtGreaterThanEqual(
                eq(NotificationStatus.SENT), any()))
                .thenReturn(0L);
        when(repository.countByParticipant_Event_IdAndStatusAndSentAtGreaterThanEqual(
                eq(eventId), eq(NotificationStatus.SENT), any()))
                .thenReturn(0L);

        assertThat(guard.allowSend(eventId)).isTrue();
        guard.recordDirectSend(eventId);
        assertThat(guard.allowSend(eventId)).isFalse();

        clock.advance(Duration.ofHours(10));
        assertThat(guard.allowSend(eventId)).isTrue();
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
