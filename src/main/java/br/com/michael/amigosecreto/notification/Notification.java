package br.com.michael.amigosecreto.notification;

import br.com.michael.amigosecreto.participant.Participant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Outbox de avisos do sorteio: uma linha por participante, por número de
 * sorteio e por canal.
 *
 * <p>É gravada na mesma transação do sorteio, então ou o resultado existe com
 * os avisos enfileirados, ou não existe. O envio em si é assíncrono; o estado
 * aqui é a única fonte de verdade sobre o que já saiu.
 *
 * <p>A chave de idempotência muda a cada reenvio deliberado, para que o
 * provedor não confunda o reenvio com uma repetição da tentativa antiga.
 */
@Entity
@Table(
        name = "notification",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_notification_participant_draw_channel",
                    columnNames = {"participant_id", "draw_count", "channel"})
        })
public class Notification {

    /** Teto de tentativas antes de desistir (e de retentativas por falha definitiva). */
    public static final int MAX_ATTEMPTS = 3;

    /** Mesmo limite da coluna: mensagem de erro maior que isso é cortada. */
    public static final int MAX_ERROR_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "participant_id", nullable = false)
    private Participant participant;

    @Column(name = "draw_count", nullable = false)
    private int drawCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private UUID idempotencyKey;

    @Column(name = "provider_message_id", length = 255)
    private String providerMessageId;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected Notification() {
        // JPA
    }

    public Notification(Participant participant, int drawCount, NotificationChannel channel) {
        this.participant = participant;
        this.drawCount = drawCount;
        this.channel = channel;
        this.status = NotificationStatus.PENDING;
        this.attempts = 0;
        this.idempotencyKey = UUID.randomUUID();
        this.createdAt = Instant.now();
    }

    /** Conta mais uma tentativa de envio antes de chamar o provedor. */
    public void markAttempt() {
        this.attempts++;
    }

    /** Envio aceito pelo provedor: guarda o id dele para rastreio e suporte. */
    public void markSent(String providerMessageId) {
        this.status = NotificationStatus.SENT;
        this.providerMessageId = providerMessageId;
        this.sentAt = Instant.now();
        this.lastError = null;
    }

    /**
     * Envio recusado. Falha definitiva (endereço inválido, por exemplo) esgota
     * as tentativas na hora, para o worker não insistir em algo que nunca vai
     * passar; falha temporária mantém o contador para a próxima rodada.
     */
    public void markFailed(String error, boolean retryable) {
        this.status = NotificationStatus.FAILED;
        this.lastError = truncate(error);
        if (!retryable) {
            this.attempts = MAX_ATTEMPTS;
        }
    }

    /**
     * Não havia para quem enviar (participante sem e-mail): não é erro nem
     * gera retentativa. O motivo fica em {@code lastError}, que é a única
     * coluna de diagnóstico da tabela.
     */
    public void markSkipped(String reason) {
        this.status = NotificationStatus.SKIPPED;
        this.lastError = truncate(reason);
    }

    /**
     * Devolve a notificação à fila com uma chave de idempotência nova, para que
     * o provedor não confunda o reenvio com uma repetição da tentativa antiga.
     */
    public void resetForRetry() {
        this.status = NotificationStatus.PENDING;
        this.attempts = 0;
        this.lastError = null;
        this.providerMessageId = null;
        this.sentAt = null;
        this.idempotencyKey = UUID.randomUUID();
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }

    public UUID getId() {
        return id;
    }

    public Participant getParticipant() {
        return participant;
    }

    public int getDrawCount() {
        return drawCount;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public UUID getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getProviderMessageId() {
        return providerMessageId;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Notification notification)) {
            return false;
        }
        return id != null && Objects.equals(id, notification.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
