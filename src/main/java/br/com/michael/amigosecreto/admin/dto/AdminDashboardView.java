package br.com.michael.amigosecreto.admin.dto;

import br.com.michael.amigosecreto.exclusion.dto.ExclusionGroupView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record AdminDashboardView(
        String eventName,
        LocalDate eventDate,
        BigDecimal suggestedValue,
        String statusLabel,
        boolean registrationOpen,
        int drawCount,
        Instant drawnAt,
        String inviteUrl,
        String inviteWhatsappUrl,
        String organizerParticipantUrl,
        String organizerWhatsappUrl,
        int participantCount,
        boolean hasDuplicateNames,
        List<ParticipantRowView> participants,
        boolean canEdit,
        boolean canAddParticipant,
        int maxParticipants,
        List<ExclusionGroupView> exclusionGroups,
        boolean canManageExclusions,
        boolean canDraw,
        String drawDisabledReason,
        boolean drawn,
        boolean redrawn,
        String drawnAtLabel) {

    public static final int MAX_PARTICIPANTS = 100;
}
