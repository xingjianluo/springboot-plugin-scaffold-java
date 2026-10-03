package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

/**
 * 单个扩展在一次刷新中的差异判定与处理结论。
 *
 * @param id     扩展 id
 * @param action 实际处理动作（新增/移除/重建/沿用）
 * @param reason 判定原因
 * @param detail 人类可读说明（依赖了谁、什么没变等）
 */
public record ExtensionChange(String id, ExtensionAction action,
                              ExtensionChangeReason reason, String detail) {

    public ExtensionChange {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(action, "action");
        java.util.Objects.requireNonNull(reason, "reason");
    }

    /** 稳定的单行描述，便于写日志。 */
    public String describe() {
        return id + " => " + action + " (" + reason
                + (detail == null || detail.isBlank() ? "" : ": " + detail) + ")";
    }
}
