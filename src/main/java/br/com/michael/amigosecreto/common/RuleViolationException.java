package br.com.michael.amigosecreto.common;

/**
 * Regra de negócio que o usuário consegue corrigir na tela, como teto de
 * participantes ou inscrições encerradas. A mensagem é sempre em pt-BR e nunca
 * carrega tokens.
 */
public class RuleViolationException extends RuntimeException {

    public RuleViolationException(String message) {
        super(message);
    }
}
