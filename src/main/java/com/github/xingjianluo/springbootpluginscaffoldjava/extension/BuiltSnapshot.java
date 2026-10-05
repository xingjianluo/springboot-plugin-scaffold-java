package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

import java.util.List;
import java.util.Objects;

/**
 * 一次离线构建的结果：可生效快照 + 本次构建真正新建的实例清单。
 * 复用池中命中的运行实例不会出现在 {@link #createdInstances()} 中，
 * 便于调用方在后续失败路径上精确清理“建出来却没启用”的实例。
 *
 * @param snapshot         离线构建并校验通过的快照（沿用实例与新建实例混排）
 * @param createdInstances 本次构建通过构造器新建的实例（按构建顺序）
 */
public record BuiltSnapshot(EnabledExtensions snapshot, List<Extension> createdInstances) {

    public BuiltSnapshot {
        Objects.requireNonNull(snapshot, "snapshot");
        createdInstances = List.copyOf(createdInstances);
    }
}
