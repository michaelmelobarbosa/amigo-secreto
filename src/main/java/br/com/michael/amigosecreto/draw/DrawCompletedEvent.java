package br.com.michael.amigosecreto.draw;

import java.time.Instant;
import java.util.UUID;

/**
 * Aviso de que o sorteio terminou, publicado somente depois do commit.
 *
 * <p>O MVP não tem listener; a fase 2 escuta este evento para enviar e-mail sem
 * acoplar a notificação à transação do sorteio. Não carrega tokens nem o
 * resultado do sorteio, apenas o evento e a contagem.
 */
public record DrawCompletedEvent(UUID eventId, int drawCount, Instant at) {
}
