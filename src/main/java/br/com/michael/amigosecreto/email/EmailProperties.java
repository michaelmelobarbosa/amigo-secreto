package br.com.michael.amigosecreto.email;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "app.email")
public record EmailProperties(
        boolean enabled,
        String fromEmail,
        String fromName,
        String replyTo,
        Brevo brevo,
        int dailyLimit,
        int eventDailyLimit) {

    public static final int DEFAULT_DAILY_LIMIT = 250;
    public static final int DEFAULT_EVENT_DAILY_LIMIT = 100;

    @ConstructorBinding
    public EmailProperties {
        dailyLimit = dailyLimit > 0 ? dailyLimit : DEFAULT_DAILY_LIMIT;
        eventDailyLimit = eventDailyLimit > 0 ? eventDailyLimit : DEFAULT_EVENT_DAILY_LIMIT;
    }

    public EmailProperties(
            boolean enabled,
            String fromEmail,
            String fromName,
            String replyTo,
            Brevo brevo) {
        this(
                enabled,
                fromEmail,
                fromName,
                replyTo,
                brevo,
                DEFAULT_DAILY_LIMIT,
                DEFAULT_EVENT_DAILY_LIMIT);
    }

    public record Brevo(String apiKey, boolean sandbox) {
    }
}
