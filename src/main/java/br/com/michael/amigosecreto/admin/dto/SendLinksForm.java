package br.com.michael.amigosecreto.admin.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;

/** E-mail de destino do envio avulso dos links do organizador. Não é persistido. */
public record SendLinksForm(
        @NotBlank(message = "Informe o e-mail que vai receber os links.")
        @Email(message = "Informe um e-mail válido.")
        @Size(max = 254, message = "O e-mail deve ter até 254 caracteres.")
        String email) {

    public SendLinksForm {
        if (email != null) {
            email = email.trim().toLowerCase(Locale.ROOT);
            if (email.isEmpty()) {
                email = null;
            }
        }
    }
}
