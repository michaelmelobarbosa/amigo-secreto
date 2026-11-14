package br.com.michael.amigosecreto.exclusion;

import br.com.michael.amigosecreto.event.Event;
import br.com.michael.amigosecreto.participant.Participant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "exclusion_group")
public class ExclusionGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(length = 80)
    private String label;

    @ManyToMany
    @JoinTable(
            name = "exclusion_group_member",
            joinColumns = @JoinColumn(name = "group_id"),
            inverseJoinColumns = @JoinColumn(name = "participant_id"))
    private Set<Participant> members = new LinkedHashSet<>();

    protected ExclusionGroup() {
        // JPA
    }

    public ExclusionGroup(Event event, String label) {
        this.event = event;
        this.label = label;
    }

    public void addMember(Participant participant) {
        members.add(participant);
    }

    public void removeMember(Participant participant) {
        members.remove(participant);
    }

    public UUID getId() {
        return id;
    }

    public Event getEvent() {
        return event;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public Set<Participant> getMembers() {
        return members;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ExclusionGroup group)) {
            return false;
        }
        return id != null && Objects.equals(id, group.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
