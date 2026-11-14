package br.com.michael.amigosecreto.participant;

import br.com.michael.amigosecreto.participant.dto.ParticipantRow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ParticipantRepository extends JpaRepository<Participant, UUID> {

    Optional<Participant> findByAccessToken(String accessToken);

    Optional<Participant> findByIdAndEvent_Id(UUID id, UUID eventId);

    Optional<Participant> findByEvent_IdAndOrganizerTrue(UUID eventId);

    long countByEvent_Id(UUID eventId);

    List<Participant> findByEvent_Id(UUID eventId);

    @Query("""
            select new br.com.michael.amigosecreto.participant.dto.ParticipantRow(
                p.id, p.name, p.accessToken, p.organizer, p.firstViewedAt)
            from Participant p
            where p.event.id = :eventId
            order by p.name, p.id
            """)
    List<ParticipantRow> findRowsByEventId(@Param("eventId") UUID eventId);

    /**
     * Grava a primeira visualização só se ela ainda não existir, sem
     * sobrescrever a data original. O filtro por evento impede que um id de
     * outro evento seja afetado.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update Participant p
            set p.firstViewedAt = :at
            where p.id = :id
              and p.event.id = :eventId
              and p.firstViewedAt is null
            """)
    int markFirstViewedIfAbsent(
            @Param("id") UUID id,
            @Param("eventId") UUID eventId,
            @Param("at") Instant at);

    /**
     * Zera a primeira visualização de todos os participantes do evento.
     *
     * <p>Usado ao refazer o sorteio e ao reabrir: o resultado anterior não vale
     * mais, então cada pessoa precisa ver o novo como se fosse a primeira vez.
     */
    @Modifying(flushAutomatically = true)
    @Query("update Participant p set p.firstViewedAt = null where p.event.id = :eventId")
    int resetFirstViewedAtByEventId(@Param("eventId") UUID eventId);
}
