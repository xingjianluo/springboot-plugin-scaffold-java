package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

import java.util.List;

/**
 * 离线计算出的增量生效计划：目标快照 + 需要重新激活/停用的实例 + 逐扩展判定。
 * 该对象不触碰任何运行状态，失败只体现在计划构建阶段，便于整体回退。
 *
 * @param targetSnapshot 切换后应对外可见的完整快照（沿用实例与新建实例混排）
 * @param toActivate     切换前需要 activate() 的实例，按目标快照激活顺序排列
 * @param toDeactivate   切换成功后需要 deactivate() 的旧实例（被移除或被替换）
 * @param changes        逐扩展差异判定与处理结论
 */
public record IncrementalPlan(EnabledExtensions targetSnapshot,
                              List<Extension> toActivate,
                              List<Extension> toDeactivate,
                              List<ExtensionChange> changes) {
    public IncrementalPlan {
        java.util.Objects.requireNonNull(targetSnapshot, "targetSnapshot");
        toActivate = List.copyOf(toActivate);
        toDeactivate = List.copyOf(toDeactivate);
        changes = List.copyOf(changes);
    }
}
