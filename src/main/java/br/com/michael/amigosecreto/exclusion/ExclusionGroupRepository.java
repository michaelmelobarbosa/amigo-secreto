package br.com.michael.amigosecreto.exclusion;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExclusionGroupRepository extends JpaRepository<ExclusionGroup, UUID> {

    List<ExclusionGroup> findByEvent_Id(UUID eventId);

    Optional<ExclusionGroup> findByIdAndEvent_Id(UUID id, UUID eventId);

    @Query("""
            select distinct g
            from ExclusionGroup g
            left join fetch g.members
            where g.event.id = :eventId
            order by g.id
            """)
    List<ExclusionGroup> findByEventIdWithMembers(@Param("eventId") UUID eventId);
}
