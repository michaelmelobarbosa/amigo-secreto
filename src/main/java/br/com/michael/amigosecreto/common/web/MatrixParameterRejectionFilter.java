package br.com.michael.amigosecreto.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejeita com 404 qualquer requisição cujo caminho contenha parâmetros de matriz ({@code ;}).
 *
 * <p>O Tomcat remove {@code ;...} antes do roteamento, o que pode gerar discrepância
 * entre o proxy de borda e a aplicação (por exemplo, {@code /actuator;x} chegando
 * como {@code /actuator}). Como o rastreamento de sessão usa apenas cookie e nenhuma
 * rota legítima utiliza ponto e vírgula no caminho, bloquear a classe inteira com 404
 * impede contorno sem confirmar existência de rotas ou tokens.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class MatrixParameterRejectionFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String requestUri = request.getRequestURI();
        if (requestUri != null && requestUri.indexOf(';') >= 0) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        filterChain.doFilter(request, response);
    }
}
