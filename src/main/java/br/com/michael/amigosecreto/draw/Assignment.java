package br.com.michael.amigosecreto.draw;

import br.com.michael.amigosecreto.event.Event;
import br.com.michael.amigosecreto.participant.Participant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Check;

@Entity
@Table(
        name = "assignment",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_assignment_event_giver",
                    columnNames = {"event_id", "giver_id"}),
            @UniqueConstraint(
                    name = "uk_assignment_event_receiver",
                    columnNames = {"event_id", "receiver_id"})
        })
@Check(name = "ck_assignment_not_self", constraints = "giver_id <> receiver_id")
public class Assignment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "giver_id", nullable = false)
    private Participant giver;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receiver_id", nullable = false)
    private Participant receiver;

    protected Assignment() {
        // JPA
    }

    public Assignment(Event event, Participant giver, Participant receiver) {
        if (giver.equals(receiver)) {
            throw new IllegalArgumentException("giver e receiver não podem ser a mesma pessoa");
        }
        this.event = event;
        this.giver = giver;
        this.receiver = receiver;
    }

    public UUID getId() {
        return id;
    }

    public Event getEvent() {
        return event;
    }

    public Participant getGiver() {
        return giver;
    }

    public Participant getReceiver() {
        return receiver;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Assignment assignment)) {
            return false;
        }
        return id != null && Objects.equals(id, assignment.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
