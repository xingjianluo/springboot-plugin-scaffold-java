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
 * <p>增量构建（{@link #build(ExtensionConfiguration, EnabledExtensions)}）：
 * 实现类与当前运行快照一致的扩展直接沿用正在运行的同一份实例，根本不重新实例化；
 * 只有新增、实现变更、或被依赖链连带波及的扩展才会创建新实例。
 * 构建失败时，本次已创建但从未启用的实例会被逐个 deactivate() 尽力回收，
 * 不会留下“建出来又丢掉”的泄漏实例。
 */
public class ExtensionPlanBuilder {

    private static final Logger log = LoggerFactory.getLogger(ExtensionPlanBuilder.class);

    private final ExtensionRegistry registry;

    public ExtensionPlanBuilder(ExtensionRegistry registry) {
        this.registry = registry;
    }

    /** 全量构建：不参考任何运行快照，所有声明一律新建实例。 */
    public EnabledExtensions build(ExtensionConfiguration configuration) {
        return build(configuration, null);
    }

    /**
     * 增量构建：与 {@code current} 中运行实例同类的声明直接复用该实例，
     * 只有真正需要新实例的声明才执行构造；依赖链连带重建也在此一并完成。
     *
     * @param configuration 目标配置
     * @param current       当前生效快照；为 null 时退化为全量构建
     */
    public EnabledExtensions build(ExtensionConfiguration configuration, EnabledExtensions current) {
        Map<String, Extension> runningByClass = new HashMap<>();
        if (current != null) {
            for (Extension running : current.extensions()) {
                runningByClass.putIfAbsent(running.getClass().getName(), running);
            }
        }
        // 本次构建新建出来的实例：一旦构建失败必须尽力回收，避免资源泄漏
        List<Extension> created = new ArrayList<>();
        try {
            return doBuild(configuration, runningByClass, created);
        } catch (ExtensionLoadException ex) {
            discardCreated(created, ex);
            throw ex;
        }
    }

    private EnabledExtensions doBuild(ExtensionConfiguration configuration,
                                      Map<String, Extension> runningByClass,
                                      List<Extension> created) {
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

            Extension running = runningByClass.get(className);
            Extension instance;
            boolean reused;
            if (running != null) {
                // 同类实现正在运行：直接沿用该实例，本次构建不产生任何新对象
                instance = running;
                reused = true;
            } else {
                instance = instantiate(type, className, failures);
                reused = false;
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
            entries.put(className, new Entry(i, declaration, instance, id, effectivePriority, reused));
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
        ordered = rebuildAffectedDependents(ordered, entries, dependencies, created, failures);
        if (!failures.isEmpty()) {
            throw new ExtensionLoadException(failures);
        }
        return new EnabledExtensions(configuration, ordered);
    }

    /**
     * 依赖连带重建：凡依赖链上有扩展拿到新实例（新增/实现变更）的“沿用”扩展，
     * 也必须换成同类的新实例，避免依赖方与被依赖方新旧混用。
     * 按拓扑序（被依赖者在前）单趟传播即可；未受波及的沿用扩展完全不触碰。
     */
    private List<Extension> rebuildAffectedDependents(List<Extension> ordered,
                                                      Map<String, Entry> entries,
                                                      Map<String, Set<String>> dependencies,
                                                      List<Extension> created,
                                                      List<ExtensionFailure> failures) {
        Map<String, Entry> byId = new HashMap<>();
        entries.values().forEach(entry -> byId.put(entry.id, entry));

        Set<String> changedIds = new HashSet<>();
        entries.values().forEach(entry -> {
            if (!entry.reused) {
                changedIds.add(entry.id);
            }
        });

        List<Extension> result = new ArrayList<>(ordered.size());
        for (Extension extension : ordered) {
            Entry entry = byId.get(extension.id());
            boolean affected = entry.reused
                    && dependencies.get(entry.id).stream().anyMatch(changedIds::contains);
            if (!affected) {
                result.add(extension);
                continue;
            }
            String className = entry.declaration.className();
            Class<? extends Extension> type = registry.findByClassName(className).orElse(null);
            Extension fresh = type == null ? null : instantiate(type, className, failures);
            if (fresh == null) {
                failures.add(new ExtensionFailure(
                        ExtensionFailureReason.INSTANTIATION_FAILED,
                        entry.id, className,
                        "dependent of a rebuilt extension must be rebuilt too, "
                                + "but re-instantiation failed"));
                continue;
            }
            created.add(fresh);
            changedIds.add(entry.id);
            log.info("dependent rebuilt together with its changed dependency: id={}, class={}",
                    entry.id, className);
            result.add(fresh);
        }
        return result;
    }

    /** 构建失败：对本次新建但从未启用的实例逐个 deactivate() 尽力回收资源。 */
    private static void discardCreated(List<Extension> created, ExtensionLoadException cause) {
        for (int i = created.size() - 1; i >= 0; i--) {
            Extension orphan = created.get(i);
            try {
                orphan.deactivate();
                log.info("discarded never-activated instance after build rejection: id={}",
                        orphan.id());
            } catch (RuntimeException ex) {
                log.warn("cleanup deactivate() failed for a never-activated instance: id={}",
                        orphan.id(), ex);
            }
        }
        if (!created.isEmpty()) {
            log.info("build rejected ({}); recycled {} never-activated instance(s)",
                    cause.getMessage(), created.size());
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
                         String id, int effectivePriority, boolean reused) {
    }
}
