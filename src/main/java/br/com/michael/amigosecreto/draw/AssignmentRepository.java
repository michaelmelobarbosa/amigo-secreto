package br.com.michael.amigosecreto.draw;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssignmentRepository extends JpaRepository<Assignment, UUID> {

    Optional<Assignment> findByEvent_IdAndGiver_Id(UUID eventId, UUID giverId);

    long countByEvent_Id(UUID eventId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Assignment a where a.event.id = :eventId")
    int deleteByEventId(@Param("eventId") UUID eventId);
}
