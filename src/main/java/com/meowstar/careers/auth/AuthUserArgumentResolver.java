package com.meowstar.careers.auth;

import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 把 {@link AuthUser} 参数解析为已认证用户：控制器只需声明 {@code AuthUser auth}，
 * 未认证 / 令牌无效时统一返回 401 JSON。
 */
@Component
public class AuthUserArgumentResolver implements HandlerMethodArgumentResolver {

    private final AuthService authService;

    public AuthUserArgumentResolver(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return AuthUser.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String header = webRequest.getHeader("Authorization");
        return authService.authenticate(header);
    }
}
