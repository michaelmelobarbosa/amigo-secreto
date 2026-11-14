package br.com.michael.amigosecreto.email;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EmailConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "app.email", name = "enabled", havingValue = "true")
    public EmailSender brevoEmailSender(EmailProperties properties) {
        return new BrevoEmailSender(properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.email", name = "enabled", havingValue = "false", matchIfMissing = true)
    public EmailSender loggingEmailSender() {
        return new LoggingEmailSender();
    }
}
