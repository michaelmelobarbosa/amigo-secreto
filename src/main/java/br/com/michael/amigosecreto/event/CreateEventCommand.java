package br.com.michael.amigosecreto.event;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Dados de entrada para criar um evento e o participante organizador.
 *
 * <p>Os campos chegam como o formulário os preencheu. A validação de formato
 * (obrigatoriedade, tamanho, data) é responsabilidade da camada web; o serviço
 * apenas monta o agregado com os valores recebidos.
 */
public record CreateEventCommand(
        String name,
        LocalDate eventDate,
        BigDecimal suggestedValue,
        String organizerName) {
}
