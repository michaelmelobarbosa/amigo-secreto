package br.com.michael.amigosecreto.email;

public interface EmailSender {

    EmailSendResult send(EmailMessage message);
}
