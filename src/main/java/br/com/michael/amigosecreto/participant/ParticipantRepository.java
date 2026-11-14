package br.com.michael.amigosecreto.participant;

import br.com.michael.amigosecreto.participant.dto.ParticipantRow;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ParticipantRepository extends JpaRepository<Participant, UUID> {

    Optional<Participant> findByAccessToken(String accessToken);

    Optional<Participant> findByIdAndEvent_Id(UUID id, UUID eventId);

    Optional<Participant> findByEvent_IdAndOrganizerTrue(UUID eventId);

    long countByEvent_Id(UUID eventId);

    @Query("""
            select new br.com.michael.amigosecreto.participant.dto.ParticipantRow(
                p.id, p.name, p.accessToken, p.organizer, p.firstViewedAt)
            from Participant p
            where p.event.id = :eventId
            order by p.name, p.id
            """)
    List<ParticipantRow> findRowsByEventId(@Param("eventId") UUID eventId);
}
