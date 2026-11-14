package br.com.michael.amigosecreto.event;

import br.com.michael.amigosecreto.exclusion.ExclusionGroup;
import br.com.michael.amigosecreto.participant.Participant;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "event")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "event_date")
    private LocalDate eventDate;

    @Column(name = "suggested_value", precision = 10, scale = 2)
    private BigDecimal suggestedValue;

    @Column(name = "admin_token", nullable = false, unique = true, length = 64)
    private String adminToken;

    @Column(name = "invite_code", nullable = false, unique = true, length = 64)
    private String inviteCode;

    @Column(name = "registration_open", nullable = false)
    private boolean registrationOpen;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventStatus status;

    @Column(name = "drawn_at")
    private Instant drawnAt;

    @Column(name = "draw_count", nullable = false)
    private int drawCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Participant> participants = new ArrayList<>();

    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ExclusionGroup> exclusionGroups = new ArrayList<>();

    protected Event() {
        // JPA
    }

    public Event(
            String name,
            LocalDate eventDate,
            BigDecimal suggestedValue,
            String adminToken,
            String inviteCode,
            Instant createdAt) {
        this.name = name;
        this.eventDate = eventDate;
        this.suggestedValue = suggestedValue;
        this.adminToken = adminToken;
        this.inviteCode = inviteCode;
        this.registrationOpen = true;
        this.status = EventStatus.OPEN;
        this.drawCount = 0;
        this.createdAt = createdAt;
    }

    @PrePersist
    void fillCreatedAtIfMissing() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public boolean isOpen() {
        return status == EventStatus.OPEN;
    }

    public boolean canJoin() {
        return isOpen() && registrationOpen;
    }

    public void markDrawn(Instant at) {
        this.status = EventStatus.DRAWN;
        this.registrationOpen = false;
        this.drawnAt = at;
        this.drawCount++;
    }

    public void reopen() {
        this.status = EventStatus.OPEN;
        this.registrationOpen = true;
        this.drawnAt = null;
    }

    public void regenerateInviteCode(String newInviteCode) {
        this.inviteCode = newInviteCode;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public LocalDate getEventDate() {
        return eventDate;
    }

    public void setEventDate(LocalDate eventDate) {
        this.eventDate = eventDate;
    }

    public BigDecimal getSuggestedValue() {
        return suggestedValue;
    }

    public void setSuggestedValue(BigDecimal suggestedValue) {
        this.suggestedValue = suggestedValue;
    }

    public String getAdminToken() {
        return adminToken;
    }

    public String getInviteCode() {
        return inviteCode;
    }

    public boolean isRegistrationOpen() {
        return registrationOpen;
    }

    public void setRegistrationOpen(boolean registrationOpen) {
        this.registrationOpen = registrationOpen;
    }

    public EventStatus getStatus() {
        return status;
    }

    public Instant getDrawnAt() {
        return drawnAt;
    }

    public int getDrawCount() {
        return drawCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<Participant> getParticipants() {
        return participants;
    }

    public List<ExclusionGroup> getExclusionGroups() {
        return exclusionGroups;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Event event)) {
            return false;
        }
        return id != null && Objects.equals(id, event.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
