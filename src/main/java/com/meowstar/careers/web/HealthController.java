package com.meowstar.careers.web;

import com.meowstar.careers.error.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 系统级端点：服务索引与健康检查（含数据库探活）。 */
@RestController
@Tag(name = "system", description = "系统 / 健康检查")
public class HealthController {

    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Operation(summary = "服务信息", description = "返回服务名、版本与文档/健康检查地址")
    @GetMapping("/")
    public Map<String, Object> index() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "meow-star-careers");
        body.put("version", "0.3.0");
        body.put("docs", "/swagger-ui/");
        body.put("openapi", "/api-docs/openapi.json");
        body.put("health", "/healthz");
        return body;
    }

    @Operation(summary = "健康检查", description = "对数据库做一次探活")
    @GetMapping("/healthz")
    public Map<String, Object> healthCheck() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
        } catch (DataAccessException ex) {
            throw ApiException.serviceUnavailable("database unreachable");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("database", "up");
        return body;
    }
}
