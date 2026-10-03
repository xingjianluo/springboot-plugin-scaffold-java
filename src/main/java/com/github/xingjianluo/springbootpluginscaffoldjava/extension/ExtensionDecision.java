package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

import java.util.Objects;

/**
 * 单个扩展在一次刷新/回退中的处理结论。
 *
 * @param id     扩展 id
 * @param action 处理动作（新增 / 移除 / 重建 / 沿用 / 恢复）
 * @param reason 这么处理的判定依据（人类可读、可解释）
 */
public record ExtensionDecision(String id, ExtensionAction action, String reason) {

    public ExtensionDecision {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(reason, "reason");
    }
}
