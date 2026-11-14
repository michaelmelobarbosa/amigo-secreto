package br.com.michael.amigosecreto.common.config;

import br.com.michael.amigosecreto.common.web.CsrfTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

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
                        "/actuator/**",
                        "/error");
    }
}
