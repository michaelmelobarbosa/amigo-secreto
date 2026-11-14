package br.com.michael.amigosecreto.email;

public record EmailSendResult(
        boolean accepted,
        String providerMessageId,
        String errorCode,
        String errorMessage,
        boolean retryable) {

    public static EmailSendResult accepted(String providerMessageId) {
        return new EmailSendResult(true, providerMessageId, null, null, false);
    }

    public static EmailSendResult rejected(
            String errorCode, String errorMessage, boolean retryable) {
        return new EmailSendResult(false, null, errorCode, errorMessage, retryable);
    }
}
