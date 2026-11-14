package br.com.michael.amigosecreto.common.web;

public class CsrfTokenException extends RuntimeException {

    public CsrfTokenException() {
        super("A solicitação não pôde ser validada.");
    }
}
