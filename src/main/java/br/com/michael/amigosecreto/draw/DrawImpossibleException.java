package br.com.michael.amigosecreto.draw;

public class DrawImpossibleException extends RuntimeException {

    public enum Reason {
        TOO_FEW_PARTICIPANTS,
        FORBIDDEN_PAIR_WITH_THREE_PARTICIPANTS,
        NO_ALLOWED_TARGET,
        NO_SOLUTION,
        SEARCH_LIMIT_EXCEEDED
    }

    private final Reason reason;

    public DrawImpossibleException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
