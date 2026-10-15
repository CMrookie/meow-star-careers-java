package com.meowstar.careers.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 文档装配（对齐 Rust 版 utoipa 的 {@code ApiDoc}）：
 * 标题/版本/描述、bearerAuth 安全方案与标签分组，供 {@code /api-docs/openapi.json} 与
 * {@code /swagger-ui/} 使用。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenApiCustomizer careersOpenApiCustomizer() {
        return openApi -> {
            openApi.setInfo(new Info()
                    .title("meow-star-careers API")
                    .version("0.3.0")
                    .description("meow-star-careers（招聘求职系统）服务端：基于 Spring Boot + PostgreSQL 的招聘求职平台后端。"
                            + "注册登录（Bearer Token）、职位发布与搜索、简历管理、在线投递与状态流转、职位收藏、"
                            + "投诉（举报）与审核账号管理；另含一对一私聊（REST + WebSocket）。"
                            + "统一错误处理、结构化日志、自动生成 API 文档。"));
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }
            openApi.getComponents().addSecuritySchemes("bearerAuth", new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer"));
            openApi.setTags(List.of(
                    new Tag().name("system").description("系统 / 健康检查"),
                    new Tag().name("auth").description("注册 / 登录 / 令牌"),
                    new Tag().name("users").description("用户管理（仅平台管理员；本人可查改自己）"),
                    new Tag().name("company").description("企业信息"),
                    new Tag().name("job").description("职位发布 / 搜索 / 收藏"),
                    new Tag().name("application").description("在线投递与状态流转"),
                    new Tag().name("resume").description("简历管理（求职者）/ 简历检索（招聘者）"),
                    new Tag().name("chat").description("一对一私聊（REST；实时通道见 /ws 与 README）"),
                    new Tag().name("interview").description("线上视频面试（房间/状态/信令经 WS 转发）"),
                    new Tag().name("complaint").description("投诉（举报）与审核（审核账号 / 平台管理员）"),
                    new Tag().name("reviewer").description("审核专用账号管理（仅平台管理员）"),
                    new Tag().name("stats").description("平台统计：审核及时性 / 用人单位优劣 / 求职用户分析")));
        };
    }

    /** 供文档页调试：默认按 bearerAuth 演示（与 Rust 版 security(("bearerAuth" = [])) 一致）。 */
    @Bean
    public OpenAPI careersOpenApi() {
        return new OpenAPI();
    }
}
