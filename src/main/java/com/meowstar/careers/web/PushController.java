package com.meowstar.careers.web;

import com.meowstar.careers.auth.AuthUser;
import com.meowstar.careers.error.ApiException;
import com.meowstar.careers.model.push.PushTokenPayload;
import com.meowstar.careers.repo.PushRepo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备推送令牌接口（预留）：客户端登录后可注册/注销，对齐 Rust 版 handlers::push。
 * POST /api/v1/push-tokens        {platform, token}
 * POST /api/v1/push-tokens/remove {platform, token}
 */
@RestController
@RequestMapping("/api/v1/push-tokens")
@Tag(name = "push", description = "设备推送令牌注册（预留：FCM/APNs 接入后读取投递）")
public class PushController {

    private final PushRepo pushRepo;

    public PushController(PushRepo pushRepo) {
        this.pushRepo = pushRepo;
    }

    private static void validate(PushTokenPayload payload) {
        String platform = payload.platform() == null ? "" : payload.platform();
        if (!"android".equals(platform) && !"ios".equals(platform) && !"web".equals(platform)) {
            throw ApiException.badRequest("platform 取值: android / ios / web");
        }
        String token = payload.token() == null ? "" : payload.token().trim();
        if (token.isEmpty() || token.length() > 512) {
            throw ApiException.badRequest("token 不能为空且不超过 512 字符");
        }
    }

    /** 注册 / 更新推送令牌（同平台同令牌幂等 upsert）。 */
    @Operation(summary = "注册推送令牌")
    @PostMapping
    public ResponseEntity<Void> registerToken(AuthUser auth, @RequestBody PushTokenPayload payload) {
        validate(payload);
        pushRepo.register(auth.userId(), payload.platform(), payload.token().trim());
        return ResponseEntity.noContent().build();
    }

    /** 注销某平台的推送令牌。 */
    @Operation(summary = "注销推送令牌")
    @PostMapping("/remove")
    public ResponseEntity<Void> removeToken(AuthUser auth, @RequestBody PushTokenPayload payload) {
        validate(payload);
        pushRepo.remove(auth.userId(), payload.platform(), payload.token().trim());
        return ResponseEntity.noContent().build();
    }
}
