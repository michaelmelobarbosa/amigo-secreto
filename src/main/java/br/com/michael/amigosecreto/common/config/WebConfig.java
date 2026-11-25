package br.com.michael.amigosecreto.common.config;

import br.com.michael.amigosecreto.common.web.CsrfTokenInterceptor;
import org.apache.catalina.session.StandardManager;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final int MAX_ACTIVE_SESSIONS = 5_000;

    private final CsrfTokenInterceptor csrfTokenInterceptor;

    public WebConfig(CsrfTokenInterceptor csrfTokenInterceptor) {
        this.csrfTokenInterceptor = csrfTokenInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(csrfTokenInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/css/**",
                        "/js/**",
                        "/images/**",
                        "/favicon.ico",
                        "/robots.txt",
                        "/actuator/**",
                        "/error");
    }

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatSessionLimitCustomizer() {
        return factory -> factory.addContextCustomizers(context -> {
            StandardManager manager = new StandardManager();
            manager.setMaxActiveSessions(MAX_ACTIVE_SESSIONS);
            manager.setPathname(null);
            context.setManager(manager);
        });
    }
}
