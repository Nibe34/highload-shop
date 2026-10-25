package ua.highload.shop.config;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Синтетична затримка для експериментів з асиметричним навантаженням (ЛР3).
 * Вмикається лише при ARTIFICIAL_DELAY_MS &gt; 0 і діє на /api/**.
 */
@Component
@ConditionalOnExpression("${shop.artificial-delay-ms:0} > 0")
public class ArtificialDelayFilter extends OncePerRequestFilter {

    private final long delayMs;

    public ArtificialDelayFilter(@Value("${shop.artificial-delay-ms}") long delayMs) {
        this.delayMs = delayMs;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        chain.doFilter(request, response);
    }
}
