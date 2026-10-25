package ua.highload.shop.config;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ua.highload.shop.db.DbRouting;

/** Додає до кожної відповіді заголовок X-Instance-ID, щоб бачити, який інстанс обробив запит. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InstanceIdFilter extends OncePerRequestFilter {

    private final String instanceId;

    public InstanceIdFilter(@Value("${shop.instance-id}") String instanceId) {
        this.instanceId = instanceId;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Instance-ID", instanceId);
        DbRouting.clearUsed();
        chain.doFilter(request, response);
    }
}
