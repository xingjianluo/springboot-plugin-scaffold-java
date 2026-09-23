package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

import java.util.Objects;

/**
 * 配置中的一条扩展声明：实现类全名 + 可选优先级覆盖。
 *
 * @param className        扩展实现类的全限定名
 * @param priorityOverride 声明顺序优先级覆盖，null 表示使用扩展自身声明
 */
public record ExtensionDeclaration(String className, Integer priorityOverride) {

    public ExtensionDeclaration {
        Objects.requireNonNull(className, "className");
        if (className.isBlank()) {
            throw new IllegalArgumentException("className must not be blank");
        }
        className = className.trim();
    }

    public static ExtensionDeclaration of(String className) {
        return new ExtensionDeclaration(className, null);
    }

    public static ExtensionDeclaration of(String className, Integer priorityOverride) {
        return new ExtensionDeclaration(className, priorityOverride);
    }
}
