package br.com.michael.amigosecreto.admin.dto;

import java.time.Instant;
import java.util.UUID;

public record ParticipantRowView(
        UUID id,
        String name,
        String participantUrl,
        String whatsappUrl,
        boolean organizer,
        Instant firstViewedAt,
        boolean duplicateName) {
}
