package br.com.michael.amigosecreto.admin.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddParticipantForm(
        @NotBlank(message = "Informe o nome.")
        @Size(max = 80, message = "O nome deve ter até 80 caracteres.")
        String name,
        @Email(message = "Informe um e-mail válido.")
        @Size(max = 254, message = "O e-mail deve ter até 254 caracteres.")
        String email,
        @Size(max = 2000, message = "A lista de desejos deve ter até 2.000 caracteres.")
        String wishlist) {
}
