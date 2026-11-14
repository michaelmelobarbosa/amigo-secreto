package br.com.michael.amigosecreto.participant.dto;

import java.time.Instant;
import java.util.UUID;

public record ParticipantRow(
        UUID id,
        String name,
        String accessToken,
        boolean organizer,
        Instant firstViewedAt) {
}
