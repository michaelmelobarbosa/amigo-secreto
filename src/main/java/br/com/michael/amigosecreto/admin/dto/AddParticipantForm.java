package br.com.michael.amigosecreto.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddParticipantForm(
        @NotBlank(message = "Informe o nome.")
        @Size(max = 80, message = "O nome deve ter até 80 caracteres.")
        String name,
        @Size(max = 2000, message = "A lista de desejos deve ter até 2.000 caracteres.")
        String wishlist) {
}
