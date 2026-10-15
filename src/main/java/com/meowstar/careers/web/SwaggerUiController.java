package com.meowstar.careers.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 文档页入口对齐：Rust 版的 Swagger UI 直接挂在 {@code /swagger-ui/}。
 * springdoc 默认把 UI 放在 {@code /swagger-ui/index.html}（{@code /swagger-ui.html} 只是跳转），
 * 这里补一个 forward，使 {@code /swagger-ui/} 与 {@code /swagger-ui} 也直接返回文档页。
 */
@Controller
public class SwaggerUiController {

    @GetMapping({"/swagger-ui", "/swagger-ui/"})
    public String swaggerUi() {
        return "forward:/swagger-ui/index.html";
    }
}
