package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把一份配置“离线”构建为可生效快照：
 * 完成白名单校验、去重、实例化、版本兼容、依赖、确定性拓扑排序；
 * 全部通过才返回快照，否则抛出携带全部原因的 {@link ExtensionLoadException}。
 * 该过程不触碰任何旧状态、不触发任何扩展回调，从而保证刷新可整体回退。
 */
public class ExtensionPlanBuilder {

    private final ExtensionRegistry registry;

    public ExtensionPlanBuilder(ExtensionRegistry registry) {
        this.registry = registry;
    }

    public EnabledExtensions build(ExtensionConfiguration configuration) {
        List<ExtensionFailure> failures = new ArrayList<>();

        // 第一次遍历：类名重复 + 白名单 + 实例化
        Map<String, Entry> entries = new LinkedHashMap<>();
        Set<String> seenClassNames = new HashSet<>();
        for (int i = 0; i < configuration.declarations().size(); i++) {
            ExtensionDeclaration declaration = configuration.declarations().get(i);
            String className = declaration.className();

            if (!seenClassNames.add(className)) {
                failures.add(new ExtensionFailure(
                        ExtensionFailureReason.DUPLICATE_DECLARATION,
                        className, className,
                        "implementation declared more than once at index " + i));
                continue;
            }

            Class<? extends Extension> type = registry.findByClassName(className).orElse(null);
            if (type == null) {
                failures.add(new ExtensionFailure(
                        ExtensionFailureReason.UNDECLARED_IMPLEMENTATION,
                        className, null,
                        "implementation is not registered in the extension registry"));
                continue;
            }

            Extension instance = instantiate(type, className, failures);
            if (instance == null) {
                continue;
            }
            String id = safeId(instance, className, failures);
            if (id == null) {
                continue;
            }
            int effectivePriority = declaration.priorityOverride() != null
                    ? declaration.priorityOverride()
                    : instance.priority();
            entries.put(className, new Entry(i, declaration, instance, id, effectivePriority));
        }

        // id 重复（不同实现类声明了相同 id）
        Map<String, String> idToClassName = new HashMap<>();
        for (Entry entry : entries.values()) {
            String previous = idToClassName.putIfAbsent(entry.id, entry.declaration.className());
            if (previous != null) {
                failures.add(new ExtensionFailure(
                        ExtensionFailureReason.DUPLICATE_DECLARATION,
                        entry.id, entry.declaration.className(),
                        "duplicate extension id also declared by " + previous));
            }
        }

        String frameworkVersion = configuration.frameworkVersion();
        Set<String> idSet = new HashSet<>();
        for (Entry entry : entries.values()) {
            idSet.add(entry.id);
        }

        // 框架版本兼容
        for (Entry entry : entries.values()) {
            String required = entry.instance.requiredFrameworkVersion();
            try {
                if (!Version.satisfies(required, frameworkVersion)) {
                    failures.add(new ExtensionFailure(
                            ExtensionFailureReason.INCOMPATIBLE_VERSION,
                            entry.id, required,
                            "requires framework >= " + required + " but actual is " + frameworkVersion));
                }
            } catch (IllegalArgumentException e) {
                throw e;
            }
        }

        // 依赖缺失
        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        for (Entry entry : entries.values()) {
            Set<String> required = new HashSet<>(entry.instance.requiredExtensions());
            dependencies.put(entry.id, required);
            for (String dep : required) {
                if (!idSet.contains(dep)) {
                    failures.add(new ExtensionFailure(
                            ExtensionFailureReason.MISSING_DEPENDENCY,
                            entry.id, dep,
                            "declared dependency is not present in the enabled set"));
                }
            }
        }

        if (!failures.isEmpty()) {
            throw new ExtensionLoadException(sortFailures(failures, entries));
        }

        List<Extension> ordered = topologicalSort(entries, dependencies, failures);
        if (!failures.isEmpty()) {
            throw new ExtensionLoadException(failures);
        }
        return new EnabledExtensions(configuration, ordered);
    }

    /**
     * 离线构建“增量计划”：以 {@code previous} 为基准对新配置做差异判定。
     * <ul>
     *   <li>实现类仍在新集合、且其（传递）依赖的实例全都沿用时，直接复用旧实例，
     *       不实例化、不触发任何生命周期回调；</li>
     *   <li>实现类发生变化（同 id 换类 / 新出现）或其依赖被换掉时，必须新建实例；</li>
     *   <li>所有校验（白名单、去重、版本、依赖、环）与全量构建一致，任一失败即抛
     *       {@link ExtensionLoadException}，且本方法全程不触碰任何旧实例。</li>
     * </ul>
     */
    public IncrementalPlan buildIncremental(ExtensionConfiguration configuration,
                                            EnabledExtensions previous) {
        List<ExtensionFailure> failures = new ArrayList<>();

        // 上一个成功快照的索引：类名 -> 运行实例，类名 -> id
        Map<String, Extension> previousByClass = new HashMap<>();
        Map<String, String> previousIdByClass = new HashMap<>();
        for (Extension old : previous.extensions()) {
            previousByClass.put(old.getClass().getName(), old);
            previousIdByClass.put(old.getClass().getName(), old.id());
        }

        // 第一次遍历：去重 + 白名单；能复用的先挂旧实例，新类才实例化
        Map<String, Entry> entries = new LinkedHashMap<>();
        Set<String> seenClassNames = new HashSet<>();
        for (int i = 0; i < configuration.declarations().size(); i++) {
            ExtensionDeclaration declaration = configuration.declarations().get(i);
            String className = declaration.className();

            if (!seenClassNames.add(className)) {
                failures.add(new ExtensionFailure(
                        ExtensionFailureReason.DUPLICATE_DECLARATION,
                        className, className,
                        "implementation declared more than once at index " + i));
                continue;
            }

            Class<? extends Extension> type = registry.findByClassName(className).orElse(null);
            if (type == null) {
                failures.add(new ExtensionFailure(
                        ExtensionFailureReason.UNDECLARED_IMPLEMENTATION,
                        className, null,
                        "implementation is not registered in the extension registry"));
                continue;
            }

            Extension reused = previousByClass.get(className);
            Extension instance;
            if (reused != null) {
                instance = reused;
            } else {
                instance = instantiate(type, className, failures);
                if (instance == null) {
                    continue;
                }
            }
            String id = safeId(instance, className, failures);
            if (id == null) {
                continue;
            }
            int effectivePriority = declaration.priorityOverride() != null
                    ? declaration.priorityOverride()
                    : instance.priority();
            entries.put(className, new Entry(i, declaration, instance, id, effectivePriority));
        }

        // id 重复（不同实现类声明了相同 id）
        Map<String, String> idToClassName = new HashMap<>();
        for (Entry entry : entries.values()) {
            String previousClassName = idToClassName.putIfAbsent(entry.id, entry.declaration.className());
            if (previousClassName != null) {
                failures.add(new ExtensionFailure(
                        ExtensionFailureReason.DUPLICATE_DECLARATION,
                        entry.id, entry.declaration.className(),
                        "duplicate extension id also declared by " + previousClassName));
            }
        }

        // reused(id) 不动点：类未变 且 所有依赖的实例也都沿用，则复用旧实例。
        // 该闭包天然覆盖“依赖被换掉/移除”引发的连带重建（传递依赖同理）。
        Map<String, Boolean> reused = new HashMap<>();
        for (Entry entry : entries.values()) {
            String oldIdForClass = previousIdByClass.get(entry.declaration.className());
            reused.put(entry.id, oldIdForClass != null && oldIdForClass.equals(entry.id));
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Entry entry : entries.values()) {
                if (!reused.get(entry.id)) {
                    continue;
                }
                for (String depId : entry.instance.requiredExtensions()) {
                    Boolean depReused = reused.get(depId);
                    if (depReused == null || !depReused) {
                        reused.put(entry.id, false);
                        changed = true;
                        break;
                    }
                }
            }
        }

        // 任何被判为重建的条目，必须是全新实例（即使类没变）：
        // 旧实例不能先停后启，且要避免新旧版本同时对外。
        for (Entry entry : entries.values()) {
            if (!reused.get(entry.id) && entry.instance == previousByClass.get(entry.declaration.className())) {
                Extension fresh = instantiate(
                        registry.findByClassName(entry.declaration.className()).orElseThrow(),
                        entry.declaration.className(), failures);
                if (fresh != null) {
                    entries.put(entry.declaration.className(),
                            new Entry(entry.index, entry.declaration, fresh, entry.id,
                                    entry.effectivePriority));
                }
            }
        }

        if (!failures.isEmpty()) {
            throw new ExtensionLoadException(sortFailures(failures, entries));
        }

        String frameworkVersion = configuration.frameworkVersion();
        Set<String> idSet = new HashSet<>();
        for (Entry entry : entries.values()) {
            idSet.add(entry.id);
        }

        // 框架版本兼容
        for (Entry entry : entries.values()) {
            String required = entry.instance.requiredFrameworkVersion();
            if (!Version.satisfies(required, frameworkVersion)) {
                failures.add(new ExtensionFailure(
                        ExtensionFailureReason.INCOMPATIBLE_VERSION,
                        entry.id, required,
                        "requires framework >= " + required + " but actual is " + frameworkVersion));
            }
        }

        // 依赖缺失
        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        for (Entry entry : entries.values()) {
            Set<String> required = new HashSet<>(entry.instance.requiredExtensions());
            dependencies.put(entry.id, required);
            for (String dep : required) {
                if (!idSet.contains(dep)) {
                    failures.add(new ExtensionFailure(
                            ExtensionFailureReason.MISSING_DEPENDENCY,
                            entry.id, dep,
                            "declared dependency is not present in the enabled set"));
                }
            }
        }

        if (!failures.isEmpty()) {
            throw new ExtensionLoadException(sortFailures(failures, entries));
        }

        List<Extension> ordered = topologicalSort(entries, dependencies, failures);
        if (!failures.isEmpty()) {
            throw new ExtensionLoadException(failures);
        }

        EnabledExtensions snapshot = new EnabledExtensions(configuration, ordered);
        List<ExtensionDecision> decisions = buildDecisions(
                previous, entries, reused, frameworkVersion, ordered);
        List<String> reusedIdList = reused.entrySet().stream()
                .filter(Map.Entry::getValue)
                .map(Map.Entry::getKey)
                .toList();
        return new IncrementalPlan(snapshot, decisions, reusedIdList);
    }

    /** 逐扩展给出处理结论与判定依据：新集合按激活顺序排列，移除项排在末尾。 */
    private static List<ExtensionDecision> buildDecisions(
            EnabledExtensions previous,
            Map<String, Entry> entries,
            Map<String, Boolean> reused,
            String frameworkVersion,
            List<Extension> ordered) {

        Map<String, String> previousClassById = new HashMap<>();
        for (Extension old : previous.extensions()) {
            previousClassById.put(old.id(), old.getClass().getName());
        }

        List<ExtensionDecision> decisions = new ArrayList<>();
        for (Extension extension : ordered) {
            String id = extension.id();
            String className = extension.getClass().getName();
            String previousClass = previousClassById.get(id);
            String note = "";
            if (!frameworkVersion.equals(previous.configuration().frameworkVersion())) {
                note = " (framework version " + previous.configuration().frameworkVersion()
                        + " -> " + frameworkVersion + ", still compatible)";
            }

            if (previousClass == null) {
                decisions.add(new ExtensionDecision(id, ExtensionAction.ADDED,
                        "not present in the previous active set, freshly instantiated: "
                                + className));
            } else if (reused.get(id)) {
                decisions.add(new ExtensionDecision(id, ExtensionAction.RETAINED,
                        "implementation and its (transitive) dependencies are unchanged, "
                                + "reusing the running instance: " + className + note));
            } else if (!previousClass.equals(className)) {
                decisions.add(new ExtensionDecision(id, ExtensionAction.RECREATED,
                        "implementation replaced: " + previousClass + " -> " + className));
            } else {
                Set<String> deps = extension.requiredExtensions();
                List<String> changedDeps = new ArrayList<>();
                for (String dep : deps) {
                    Boolean depReused = reused.get(dep);
                    if (depReused != null && !depReused) {
                        changedDeps.add(dep);
                    }
                }
                decisions.add(new ExtensionDecision(id, ExtensionAction.RECREATED,
                        "implementation unchanged but rebuilt in cascade because the instance "
                                + "of a declared dependency changed: " + changedDeps));
            }
        }

        // 移除项：新集合不再包含的旧 id，按旧快照逆序报告（与逆序停用一致）
        Set<String> newIds = new HashSet<>();
        ordered.forEach(e -> newIds.add(e.id()));
        for (int i = previous.extensions().size() - 1; i >= 0; i--) {
            Extension old = previous.extensions().get(i);
            if (!newIds.contains(old.id())) {
                decisions.add(new ExtensionDecision(old.id(), ExtensionAction.REMOVED,
                        "no longer declared in the new configuration, instance will be "
                                + "deactivated after switch"));
            }
        }
        return decisions;
    }

    /**
     * 增量计划：待生效快照 + 逐扩展结论 + 被沿用（不重建）的 id 集合。
     */
    public record IncrementalPlan(EnabledExtensions snapshot,
                                  List<ExtensionDecision> decisions,
                                  List<String> reusedIds) {
        public IncrementalPlan {
            decisions = List.copyOf(decisions);
            reusedIds = List.copyOf(reusedIds);
        }
    }

    private static Extension instantiate(Class<? extends Extension> type, String className,
                                         List<ExtensionFailure> failures) {
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (NoSuchMethodException e) {
            failures.add(new ExtensionFailure(
                    ExtensionFailureReason.INSTANTIATION_FAILED,
                    className, null, "no accessible no-arg constructor"));
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            String cause = e.getCause() != null ? e.getCause().toString() : e.toString();
            failures.add(new ExtensionFailure(
                    ExtensionFailureReason.INSTANTIATION_FAILED,
                    className, null, "constructor invocation failed: " + cause));
        }
        return null;
    }

    private static String safeId(Extension instance, String className, List<ExtensionFailure> failures) {
        try {
            String id = instance.id();
            if (id == null || id.isBlank()) {
                failures.add(new ExtensionFailure(
                        ExtensionFailureReason.INSTANTIATION_FAILED,
                        className, null, "extension id() returned a blank value"));
                return null;
            }
            return id.trim();
        } catch (RuntimeException e) {
            failures.add(new ExtensionFailure(
                    ExtensionFailureReason.INSTANTIATION_FAILED,
                    className, null, "id() threw: " + e));
            return null;
        }
    }

    /**
     * Kahn 拓扑排序：被依赖者排在前面；同一就绪层按 (优先级, 声明下标) 选择，保证稳定可复现。
     * 无法排出的剩余节点即构成环，逐个报告并列出环上全部对象。
     */
    private static List<Extension> topologicalSort(Map<String, Entry> entries,
                                                   Map<String, Set<String>> dependencies,
                                                   List<ExtensionFailure> failures) {
        Map<String, Entry> byId = new HashMap<>();
        entries.values().forEach(entry -> byId.put(entry.id, entry));

        Map<String, Integer> indegree = new HashMap<>();
        Map<String, Set<String>> dependents = new HashMap<>();
        for (String id : dependencies.keySet()) {
            indegree.put(id, 0);
            dependents.put(id, new HashSet<>());
        }
        for (Map.Entry<String, Set<String>> e : dependencies.entrySet()) {
            for (String dep : e.getValue()) {
                indegree.merge(e.getKey(), 1, Integer::sum);
                dependents.get(dep).add(e.getKey());
            }
        }

        Comparator<String> tieBreaker = Comparator
                .comparingInt((String id) -> byId.get(id).effectivePriority)
                .thenComparingInt(id -> byId.get(id).index);
        java.util.TreeSet<String> readySet = new java.util.TreeSet<>(tieBreaker);
        indegree.forEach((id, degree) -> {
            if (degree == 0) {
                readySet.add(id);
            }
        });

        List<Extension> ordered = new ArrayList<>();
        while (!readySet.isEmpty()) {
            String id = readySet.pollFirst();
            ordered.add(byId.get(id).instance);
            for (String dependent : dependents.get(id)) {
                if (indegree.merge(dependent, -1, Integer::sum) == 0) {
                    readySet.add(dependent);
                }
            }
        }

        if (ordered.size() != dependencies.size()) {
            List<Extension> orderedInstances = ordered;
            List<String> cycle = dependencies.keySet().stream()
                    .filter(id -> !orderedInstances.contains(byId.get(id).instance))
                    .sorted(tieBreaker)
                    .toList();
            String joined = String.join(",", cycle);
            for (String id : cycle) {
                failures.add(new ExtensionFailure(
                        ExtensionFailureReason.CIRCULAR_DEPENDENCY,
                        id, joined,
                        "extensions form a dependency cycle: " + joined));
            }
        }
        return ordered;
    }

    private static List<ExtensionFailure> sortFailures(List<ExtensionFailure> failures,
                                                       Map<String, Entry> entries) {
        Map<String, Integer> sourceOrder = new HashMap<>();
        entries.forEach((className, entry) -> {
            sourceOrder.put(className, entry.index);
            sourceOrder.put(entry.id, entry.index);
        });
        return failures.stream()
                .sorted(Comparator
                        .comparingInt((ExtensionFailure f) ->
                                sourceOrder.getOrDefault(f.source(), Integer.MAX_VALUE))
                        .thenComparing(ExtensionFailure::reason)
                        .thenComparing(ExtensionFailure::describe))
                .toList();
    }

    private record Entry(int index, ExtensionDeclaration declaration, Extension instance,
                         String id, int effectivePriority) {
    }
}
