package br.com.michael.amigosecreto.notification;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Acesso ao outbox. As consultas por participante, sorteio e canal refletem a
 * regra estrutural do banco ({@code uk_notification_participant_draw_channel}):
 * no máximo uma notificação por pessoa a cada sorteio, em cada canal.
 *
 * <p>Quem lê {@code participant} a partir daqui precisa estar dentro de uma
 * transação: a relação é {@code LAZY} e o projeto roda com
 * {@code open-in-view=false}.
 */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /** Já existe aviso enfileirado para esta pessoa neste sorteio e canal? */
    boolean existsByParticipant_IdAndDrawCountAndChannel(
            UUID participantId, int drawCount, NotificationChannel channel);

    Optional<Notification> findByParticipant_IdAndDrawCountAndChannel(
            UUID participantId, int drawCount, NotificationChannel channel);

    /** Todos os avisos de um sorteio, por canal. Usado para montar o painel. */
    List<Notification> findByParticipant_Event_IdAndDrawCountAndChannel(
            UUID eventId, int drawCount, NotificationChannel channel);

    /**
     * Lote do worker: só estados que valem nova tentativa e abaixo do teto de
     * tentativas, do mais antigo para o mais novo.
     */
    List<Notification> findTop20ByStatusInAndAttemptsLessThanOrderByCreatedAtAsc(
            Collection<NotificationStatus> statuses, int attempts);

    @Modifying(flushAutomatically = true)
    @Query("delete from Notification n where n.participant.id = :participantId")
    int deleteByParticipantId(@Param("participantId") UUID participantId);

    @Modifying(flushAutomatically = true)
    @Query("delete from Notification n where n.participant.event.id = :eventId")
    int deleteByEventId(@Param("eventId") UUID eventId);
}
