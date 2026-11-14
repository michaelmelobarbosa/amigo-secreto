package br.com.michael.amigosecreto.event;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

public record CreateEventForm(
        @NotBlank(message = "Informe o nome do evento.")
        @Size(max = 80, message = "O nome do evento deve ter no máximo 80 caracteres.")
        String name,

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate eventDate,

        @DecimalMin(value = "0.00", message = "O valor sugerido não pode ser negativo.")
        @Digits(integer = 8, fraction = 2, message = "Use no máximo 2 casas decimais.")
        BigDecimal suggestedValue,

        @NotBlank(message = "Informe o nome do organizador.")
        @Size(max = 80, message = "O nome do organizador deve ter no máximo 80 caracteres.")
        String organizerName) {
}
