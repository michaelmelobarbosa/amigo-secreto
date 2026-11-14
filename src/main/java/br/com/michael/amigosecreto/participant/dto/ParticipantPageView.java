package br.com.michael.amigosecreto.participant.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ParticipantPageView(
        String eventName,
        LocalDate eventDate,
        BigDecimal suggestedValue,
        boolean drawn,
        String ownName,
        String ownWishlist,
        String receiverName,
        String receiverWishlist,
        boolean redrawn,
        String drawnAtLabel) {
}
