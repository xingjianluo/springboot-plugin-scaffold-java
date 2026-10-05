package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 在“旧生效快照”与“已离线校验通过的新计划”之间计算增量：
 * 决定每个扩展是新增、移除、重建还是沿用，以及依赖变化引起的连带重建。
 * 不触发任何生命周期回调、不修改任何运行状态。
 *
 * <p>沿用判定（同一 id）：
 * <ul>
 *   <li>目标快照中的就是当前正在运行的同一份实例 → 沿用，从头到尾不触碰；</li>
 *   <li>实现类变化 → 重建；</li>
 *   <li>同类但换了一份新实例（增量构建判定其依赖链有变化）→ 重建；</li>
 *   <li>仅优先级覆盖变化 → 沿用实例，仅生效顺序改变；</li>
 *   <li>但只要其依赖链上有任何扩展被新增/重建，就连带升级为重建，
 *       避免依赖方与被依赖方的新旧版本在同一时刻混用。</li>
 * </ul>
 */
public class ExtensionRefreshPlanner {

    /**
     * @param current 当前生效快照（可能为空快照）
     * @param target  离线构建并校验通过的新快照（沿用项为运行中的同一份实例，
     *                新增/重建项为本次新建实例）
     * @return 增量生效计划
     */
    public IncrementalPlan plan(EnabledExtensions current, EnabledExtensions target) {
        Map<String, Extension> currentById = indexById(current);
        Map<String, Extension> targetById = indexById(target);
        Map<String, ExtensionDeclaration> currentDecl = declarationsByClass(current);
        Map<String, ExtensionDeclaration> targetDecl = declarationsByClass(target);

        // 初步判定：新增 / 移除 / 实现变更 / 仅顺序变更 / 不变
        Map<String, Decision> decisions = new LinkedHashMap<>();
        for (Extension fresh : target.extensions()) {
            String id = fresh.id();
            Extension old = currentById.get(id);
            if (old == null) {
                decisions.put(id, new Decision(ExtensionAction.ADDED,
                        ExtensionChangeReason.NEW_ENABLED,
                        "newly enabled implementation " + fresh.getClass().getName()));
                continue;
            }
            String oldClass = old.getClass().getName();
            String newClass = fresh.getClass().getName();
            if (old != fresh && !oldClass.equals(newClass)) {
                decisions.put(id, new Decision(ExtensionAction.REBUILT,
                        ExtensionChangeReason.IMPLEMENTATION_CHANGED,
                        "implementation changed from " + oldClass + " to " + newClass));
                continue;
            }
            if (old != fresh) {
                // 同类但已换成新实例：增量构建判定其依赖链有变化，必须连带重建
                decisions.put(id, new Decision(ExtensionAction.REBUILT,
                        ExtensionChangeReason.DEPENDENCY_REBUILT,
                        "a dependency of this extension is added or rebuilt; "
                                + "dependent must be rebuilt together "
                                + "to avoid mixing old and new instances"));
                continue;
            }
            // old == fresh：正在运行的同一份实例，直接沿用
            Integer oldPrio = priorityOf(old, currentDecl);
            Integer newPrio = priorityOf(fresh, targetDecl);
            if (!java.util.Objects.equals(oldPrio, newPrio)) {
                decisions.put(id, new Decision(ExtensionAction.REUSED,
                        ExtensionChangeReason.ORDER_ONLY_CHANGED,
                        "priority override changed " + oldPrio + " -> " + newPrio
                                + "; runtime instance retained, only activation order changes"));
            } else {
                decisions.put(id, new Decision(ExtensionAction.REUSED,
                        ExtensionChangeReason.UNCHANGED,
                        "implementation and dependencies unchanged; same running instance retained"));
            }
        }
        for (Extension old : current.extensions()) {
            decisions.putIfAbsent(old.id(), new Decision(ExtensionAction.REMOVED,
                    ExtensionChangeReason.REMOVED_FROM_CONFIG,
                    "no longer declared in the enabled configuration"));
        }

        // 连带重建：依赖的对象被新增/重建，则依赖方也必须重建；按依赖图传播到不动点
        Set<String> instanceChangedIds = new java.util.HashSet<>();
        decisions.forEach((id, decision) -> {
            if (decision.action == ExtensionAction.ADDED || decision.action == ExtensionAction.REBUILT) {
                instanceChangedIds.add(id);
            }
        });
        Deque<String> workQueue = new ArrayDeque<>(instanceChangedIds);
        while (!workQueue.isEmpty()) {
            String changedId = workQueue.poll();
            for (Extension fresh : targetById.values()) {
                String dependentId = fresh.id();
                if (!fresh.requiredExtensions().contains(changedId)) {
                    continue;
                }
                Decision decision = decisions.get(dependentId);
                if (decision != null && decision.action == ExtensionAction.REUSED) {
                    decisions.put(dependentId, new Decision(ExtensionAction.REBUILT,
                            ExtensionChangeReason.DEPENDENCY_REBUILT,
                            "dependency '" + changedId
                                    + "' is added or rebuilt; dependent must be rebuilt together "
                                    + "to avoid mixing old and new instances"));
                    instanceChangedIds.add(dependentId);
                    workQueue.add(dependentId);
                }
            }
        }

        // 组装目标有序快照：沿用旧实例，新增/重建使用新实例
        List<Extension> orderedTarget = new ArrayList<>();
        List<Extension> toActivate = new ArrayList<>();
        for (Extension fresh : target.extensions()) {
            String id = fresh.id();
            Decision decision = decisions.get(id);
            if (decision.action == ExtensionAction.REUSED) {
                orderedTarget.add(currentById.get(id));
            } else {
                orderedTarget.add(fresh);
                toActivate.add(fresh);
            }
        }

        // 切换成功后需要停用的旧实例：被移除或被替换；按旧激活顺序的逆序执行
        List<Extension> toDeactivate = new ArrayList<>();
        for (int i = current.extensions().size() - 1; i >= 0; i--) {
            Extension old = current.extensions().get(i);
            Decision decision = decisions.get(old.id());
            if (decision != null && decision.action != ExtensionAction.REUSED) {
                toDeactivate.add(old);
            }
        }

        // 逐扩展结论：目标快照顺序在前，被移除者按旧顺序附后
        List<ExtensionChange> changes = new ArrayList<>();
        for (Extension fresh : target.extensions()) {
            changes.add(toChange(fresh.id(), decisions.get(fresh.id())));
        }
        for (Extension old : current.extensions()) {
            if (!targetById.containsKey(old.id())) {
                changes.add(toChange(old.id(), decisions.get(old.id())));
            }
        }

        EnabledExtensions targetSnapshot = new EnabledExtensions(target.configuration(), orderedTarget);
        return new IncrementalPlan(targetSnapshot, toActivate, toDeactivate, changes);
    }

    /**
     * 计算“回到上一次成功快照”的计划：直接复用 previous 中记录在案的实例，
     * 不做任何重建——回退后运行状态须与当时一致，而不是照配置重新猜测。
     *
     * @param current  当前生效快照
     * @param previous 上一次成功生效的快照
     * @return 增量生效计划（目标快照即 previous 本身）
     */
    public IncrementalPlan restorePlan(EnabledExtensions current, EnabledExtensions previous) {
        Map<String, Extension> currentById = indexById(current);

        List<Extension> toActivate = new ArrayList<>();
        List<ExtensionChange> changes = new ArrayList<>();
        for (Extension extension : previous.extensions()) {
            Extension currentInstance = currentById.get(extension.id());
            if (currentInstance == extension) {
                // 当前仍在运行的同一份实例：继续沿用，不回调
                changes.add(new ExtensionChange(extension.id(), ExtensionAction.REUSED,
                        ExtensionChangeReason.UNCHANGED,
                        "same instance kept running through restore; not stopped or reactivated"));
            } else {
                // 上一快照的实例在后续刷新中已被停用：恢复时重新激活同一份实例
                toActivate.add(extension);
                changes.add(new ExtensionChange(extension.id(), ExtensionAction.REBUILT,
                        ExtensionChangeReason.RESTORED_FROM_PREVIOUS,
                        "the exact instance from the last successful snapshot is reactivated "
                                + "(no reconstruction from configuration)"));
            }
        }

        List<Extension> toDeactivate = new ArrayList<>();
        for (int i = current.extensions().size() - 1; i >= 0; i--) {
            Extension old = current.extensions().get(i);
            Extension previousInstance = previous.byId(old.id());
            if (previousInstance == null) {
                // 上一配置中不存在：回退后移除
                toDeactivate.add(old);
                changes.add(new ExtensionChange(old.id(), ExtensionAction.REMOVED,
                        ExtensionChangeReason.REMOVED_FROM_CONFIG,
                        "not part of the previous successful configuration being restored"));
            } else if (previousInstance != old) {
                // 同 id 但当前运行的是后来替换出的另一份实例：恢复旧实例前先停掉替换者，
                // 避免新旧两份实例在回退后同时运行
                toDeactivate.add(old);
            }
        }

        return new IncrementalPlan(previous, toActivate, toDeactivate, changes);
    }

    private static Map<String, Extension> indexById(EnabledExtensions snapshot) {        Map<String, Extension> byId = new LinkedHashMap<>();
        snapshot.extensions().forEach(extension -> byId.put(extension.id(), extension));
        return byId;
    }

    private static Map<String, ExtensionDeclaration> declarationsByClass(EnabledExtensions snapshot) {
        Map<String, ExtensionDeclaration> byClass = new HashMap<>();
        snapshot.configuration().declarations()
                .forEach(declaration -> byClass.put(declaration.className(), declaration));
        return byClass;
    }

    private static Integer priorityOf(Extension extension,
                                     Map<String, ExtensionDeclaration> declarations) {
        ExtensionDeclaration declaration = declarations.get(extension.getClass().getName());
        return declaration == null ? null : declaration.priorityOverride();
    }

    private static ExtensionChange toChange(String id, Decision decision) {
        return new ExtensionChange(id, decision.action, decision.reason, decision.detail);
    }

    private record Decision(ExtensionAction action, ExtensionChangeReason reason, String detail) {
    }
}
