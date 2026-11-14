package br.com.michael.amigosecreto.admin.dto;

import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record ExclusionGroupForm(
        @Size(max = 80, message = "O rótulo deve ter até 80 caracteres.")
        String label,
        @Size(min = 2, message = "Selecione pelo menos 2 participantes.")
        List<UUID> memberIds) {

    public ExclusionGroupForm {
        if (memberIds == null) {
            memberIds = List.of();
        }
    }
}
