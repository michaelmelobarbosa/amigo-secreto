package br.com.michael.amigosecreto.exclusion.dto;

import java.util.List;
import java.util.UUID;

/**
 * Grupo de exclusão pronto para a tela do painel.
 *
 * <p>Os membros chegam como nomes porque a view roda fora da transação
 * ({@code open-in-view=false}) e não pode tocar na coleção lazy do grupo.
 */
public record ExclusionGroupView(UUID id, String label, List<String> memberNames) {
}
