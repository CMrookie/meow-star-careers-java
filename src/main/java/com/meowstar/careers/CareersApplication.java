package com.meowstar.careers;

import com.meowstar.careers.config.DotenvLoader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 入口：初始化日志 -> 加载配置 -> 连接数据库并迁移 -> 引导管理员 -> 启动 WebSocket Hub -> 启动 HTTP 服务器。
 * 模块分层：config(配置) / db(连接池+迁移) / auth(鉴权) / security(哈希与令牌) / limiter(限流)
 *           / ws(WebSocket 实时通道) / model(模型) / repo(数据访问) / web(HTTP) / error(统一错误)
 */
@SpringBootApplication
public class CareersApplication {

    public static void main(String[] args) {
        // 与 Rust 版 dotenvy::dotenv() 对齐：存在 .env 时自动加载（真实环境变量优先）
        DotenvLoader.loadIfPresent();
        SpringApplication.run(CareersApplication.class, args);
    }
}
