package br.com.michael.amigosecreto.draw;

import java.security.SecureRandom;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Fonte de aleatoriedade do sorteio.
 *
 * <p>O solver é puro e recebe o gerador por construtor: em produção usamos
 * {@link SecureRandom} para que a ordem dos candidatos não seja previsível.
 * Testes unitários continuam criando o solver com semente fixa.
 */
@Configuration
public class DrawConfig {

    @Bean
    CycleDrawSolver cycleDrawSolver() {
        return new CycleDrawSolver(new SecureRandom());
    }
}
