package br.com.michael.amigosecreto.participant.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Linha do painel do organizador. Projeção fechada: não carrega entidade nem
 * relação lazy, então pode ser lida fora da transação
 * ({@code open-in-view=false}).
 */
public record ParticipantRow(
        UUID id,
        String name,
        String accessToken,
        String email,
        boolean organizer,
        Instant firstViewedAt) {
}
