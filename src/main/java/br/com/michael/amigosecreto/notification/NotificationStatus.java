package br.com.michael.amigosecreto.notification;

/**
 * Ciclo de vida de uma notificação no outbox.
 *
 * <p>{@code FAILED} é uma falha temporária que ainda pode ser retentada pelo
 * worker enquanto {@code attempts < 3}; uma falha definitiva esgota as
 * tentativas na hora. Um reenvio explícito do organizador volta para
 * {@code PENDING} com {@link Notification#resetForRetry()}.
 */
public enum NotificationStatus {
    PENDING,
    SENT,
    FAILED,
    SKIPPED
}
