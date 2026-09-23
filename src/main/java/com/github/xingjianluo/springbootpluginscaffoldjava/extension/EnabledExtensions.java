package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

import java.util.List;
import java.util.Objects;

/**
 * 已生效扩展的不可变快照，顺序即为最终激活顺序（依赖在前，优先级次之，声明顺序兜底）。
 */
public record EnabledExtensions(ExtensionConfiguration configuration, List<Extension> extensions) {

    public EnabledExtensions {
        Objects.requireNonNull(configuration, "configuration");
        extensions = List.copyOf(extensions);
    }

    public static EnabledExtensions empty(ExtensionConfiguration configuration) {
        return new EnabledExtensions(configuration, List.of());
    }

    public List<String> ids() {
        return extensions.stream().map(Extension::id).toList();
    }

    public Extension byId(String id) {
        return extensions.stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
    }
}
