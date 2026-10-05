package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *
 * <p>支持“复用池”构建：调用方可把当前正在运行的实例按实现类名传入，
 * 命中的声明直接沿用该实例及其元数据，<b>不再调用构造器</b>——
 * 未变化的扩展在整个刷新过程中自始至终只有运行中的那一份实例。
 * 校验失败时，本次构建新建的实例会被尽力 {@link Extension#deactivate()} 回收，
 * 不会留下“建出来又没启用就丢掉”的实例占用资源。
 */
public class ExtensionPlanBuilder {

    private static final Logger log = LoggerFactory.getLogger(ExtensionPlanBuilder.class);

    private final ExtensionRegistry registry;

    public ExtensionPlanBuilder(ExtensionRegistry registry) {
        this.registry = registry;
    }

    /** 无复用池的全量构建：每个声明都新建实例（用于首次加载等场景）。 */
    public EnabledExtensions build(ExtensionConfiguration configuration) {
        return build(configuration, Map.of()).snapshot();
    }

    /**
     * 带复用池的构建。
     *
     * @param configuration   提交的启用配置
     * @param reusableByClass 可复用实例池：实现类全名 -&gt; 正在运行（或本次刷新已建好）的实例；
     *                        命中的声明不再实例化，直接沿用该对象
     * @return 可生效快照 + 本次调用真正新建的实例清单
     * @throws ExtensionLoadException 校验失败；抛出前已尽力停用本次新建的实例
     */
    public BuiltSnapshot build(ExtensionConfiguration configuration,
                               Map<String, Extension> reusableByClass) {
        List<ExtensionFailure> failures = new ArrayList<>();
        List<Extension> created = new ArrayList<>();

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

            Extension instance;
            Extension reusable = reusableByClass.get(className);
            if (reusable != null && reusable.getClass().getName().equals(className)) {
                // 命中复用池：沿用同一份实例，不调用构造器，创建次数保持为零
                instance = reusable;
                log.debug("reusing running instance for declared class: {}", className);
            } else {
                instance = instantiate(type, className, failures);
                if (instance != null) {
                    created.add(instance);
                }
            }
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
            deactivateCreated(created, failures);
            throw new ExtensionLoadException(sortFailures(failures, entries));
        }

        List<Extension> ordered = topologicalSort(entries, dependencies, failures);
        if (!failures.isEmpty()) {
            deactivateCreated(created, failures);
            throw new ExtensionLoadException(failures);
        }
        return new BuiltSnapshot(new EnabledExtensions(configuration, ordered), created);
    }

    /** 校验失败时尽力停用本次新建的实例，避免“建出来又没启用”的实例占用资源。 */
    private static void deactivateCreated(List<Extension> created, List<ExtensionFailure> failures) {
        for (int i = created.size() - 1; i >= 0; i--) {
            Extension instance = created.get(i);
            try {
                instance.deactivate();
                log.info("discarded instance deactivated after build rejection: id={}",
                        safeIdForLog(instance));
            } catch (RuntimeException ex) {
                log.warn("deactivate() of a discarded instance failed: id={}",
                        safeIdForLog(instance), ex);
            }
        }
    }

    private static String safeIdForLog(Extension instance) {
        try {
            return instance.id();
        } catch (RuntimeException ex) {
            return instance.getClass().getName();
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
