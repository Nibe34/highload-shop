package ua.highload.shop.db;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Додає заголовок X-DB-Node (primary | replica | primary-fallback) — який вузол СУБД обслужив запит. */
@RestControllerAdvice
public class DbNodeHeaderAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        String node = DbRouting.used();
        if (node != null) {
            response.getHeaders().set("X-DB-Node", node);
        }
        DbRouting.clearUsed();
        return body;
    }
}
