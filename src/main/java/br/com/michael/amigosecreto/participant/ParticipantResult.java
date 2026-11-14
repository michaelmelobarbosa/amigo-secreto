package br.com.michael.amigosecreto.participant;

/**
 * O que o participante pode ver do amigo sorteado: nome e wishlist. Não expõe
 * id, evento nem token.
 */
public record ParticipantResult(String receiverName, String receiverWishlist) {
}
