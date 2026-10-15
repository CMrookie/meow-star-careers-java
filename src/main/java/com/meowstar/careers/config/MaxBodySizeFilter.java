package com.meowstar.careers.config;

import com.meowstar.careers.error.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 请求体上限（默认 1 MiB，对齐 Rust 版 {@code web::JsonConfig::limit}）：
 * 超限直接返回统一 400 JSON，避免大报文进入反序列化。
 */
@Component
public class MaxBodySizeFilter extends OncePerRequestFilter {

    private final long maxBytes;
    private final ObjectMapper objectMapper;

    public MaxBodySizeFilter(AppProps props, ObjectMapper objectMapper) {
        this.maxBytes = props.maxRequestBytes();
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > maxBytes) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getWriter(),
                    new ErrorResponse("bad_request", "malformed or invalid JSON request body"));
            return;
        }
        chain.doFilter(request, response);
    }
}
