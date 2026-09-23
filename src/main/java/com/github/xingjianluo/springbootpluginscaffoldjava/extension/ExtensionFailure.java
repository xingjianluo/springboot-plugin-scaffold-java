package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

import java.util.Objects;

/**
 * 单条加载失败记录：原因 + 触发来源 + 冲突对象 + 说明。
 *
 * @param reason   可区分的失败原因
 * @param source   触发失败的来源（通常是扩展 id 或声明类名）
 * @param conflict 冲突对象（依赖的 id、冲突的重复声明等），无则为 null
 * @param detail   人类可读说明
 */
public record ExtensionFailure(
        ExtensionFailureReason reason,
        String source,
        String conflict,
        String detail) {

    public ExtensionFailure {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(source, "source");
    }

    /** 输出稳定、可解释的单行描述。 */
    public String describe() {
        StringBuilder sb = new StringBuilder()
                .append('[').append(reason).append("] source=").append(source);
        if (conflict != null) {
            sb.append(", conflict=").append(conflict);
        }
        if (detail != null) {
            sb.append(", detail=").append(detail);
        }
        return sb.toString();
    }
}
