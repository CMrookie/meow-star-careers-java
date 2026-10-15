package com.meowstar.careers.model.user;

import java.util.UUID;

/** 对他人可见的最小用户信息（会话对端等场景）。 */
public record UserPublic(UUID id, String name) {
}
