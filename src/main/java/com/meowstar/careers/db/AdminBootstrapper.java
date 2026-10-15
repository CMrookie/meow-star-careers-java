package com.meowstar.careers.db;

import com.meowstar.careers.config.AppProps;
import com.meowstar.careers.error.ApiException;
import com.meowstar.careers.repo.AuthRepo;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/**
 * 平台管理员引导（可选）：配置了 ADMIN_PHONE / ADMIN_PASSWORD 时幂等创建 admin 账号，
 * 否则投诉审核没有可用的账号（admin 不可自助注册）。
 * 手机号已被其它角色占用时服务启动失败（绝不把既有账号提权）。
 */
@Component
@DependsOn("migrator")
public class AdminBootstrapper {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapper.class);

    private final AppProps props;
    private final AuthRepo authRepo;

    public AdminBootstrapper(AppProps props, AuthRepo authRepo) {
        this.props = props;
        this.authRepo = authRepo;
    }

    @PostConstruct
    public void bootstrap() {
        if (props.adminPhone() == null || props.adminPassword() == null) {
            return;
        }
        try {
            AuthRepo.AdminBootstrap result =
                    authRepo.ensureAdmin(props.adminPhone(), props.adminName(), props.adminPassword());
            if (result == AuthRepo.AdminBootstrap.CREATED) {
                log.info("已引导创建平台管理员账号 admin_phone={}", props.adminPhone());
            } else {
                log.info("平台管理员账号已存在（密码保持不变） admin_phone={}", props.adminPhone());
            }
        } catch (ApiException ex) {
            throw new IllegalStateException("平台管理员引导失败：" + ex.getMessage(), ex);
        }
    }
}
