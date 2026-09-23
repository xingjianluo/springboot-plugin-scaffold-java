package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 一次刷新所提交的扩展配置：框架版本 + 按声明顺序排列的实现列表。
 */
public record ExtensionConfiguration(String frameworkVersion, List<ExtensionDeclaration> declarations) {

    public ExtensionConfiguration {
        Objects.requireNonNull(frameworkVersion, "frameworkVersion");
        declarations = List.copyOf(declarations);
    }

    public static ExtensionConfiguration of(String frameworkVersion, List<ExtensionDeclaration> declarations) {
        return new ExtensionConfiguration(frameworkVersion,
                declarations == null ? List.of() : declarations);
    }

    /**
     * 用于幂等判断的稳定指纹：框架版本 + 声明（类名,优先级）序列。
     * 指纹相同的两次提交视为同一配置，刷新将被跳过。
     */
    public String fingerprint() {
        return "fw=" + frameworkVersion.trim()
                + ";ext=" + declarations.stream()
                .map(d -> d.className() + (d.priorityOverride() == null ? "" : "#" + d.priorityOverride()))
                .collect(Collectors.joining(","));
    }
}
