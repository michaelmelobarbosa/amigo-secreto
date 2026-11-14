package br.com.michael.amigosecreto.participant;

import br.com.michael.amigosecreto.event.Event;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "participant")
public class Participant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "access_token", nullable = false, unique = true, length = 64)
    private String accessToken;

    @Column(length = 2000)
    private String wishlist;

    @Column(nullable = false)
    private boolean organizer;

    @Column(name = "first_viewed_at")
    private Instant firstViewedAt;

    protected Participant() {
        // JPA
    }

    public Participant(Event event, String name, String accessToken, boolean organizer) {
        this.event = event;
        this.name = name;
        this.accessToken = accessToken;
        this.organizer = organizer;
    }

    public void markViewed(Instant at) {
        if (firstViewedAt == null) {
            firstViewedAt = at;
        }
    }

    public UUID getId() {
        return id;
    }

    public Event getEvent() {
        return event;
    }

    public String getName() {
        return name;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getWishlist() {
        return wishlist;
    }

    public void setWishlist(String wishlist) {
        this.wishlist = wishlist;
    }

    public boolean isOrganizer() {
        return organizer;
    }

    public Instant getFirstViewedAt() {
        return firstViewedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Participant participant)) {
            return false;
        }
        return id != null && Objects.equals(id, participant.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
