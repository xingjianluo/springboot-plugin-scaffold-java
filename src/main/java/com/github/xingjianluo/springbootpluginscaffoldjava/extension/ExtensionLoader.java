package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 扩展加载器：根据配置声明完成校验、实例化、依赖与兼容性判定，
 * 输出顺序稳定的不可变快照。任何一个环节失败都会整体拒绝，不产生部分生效状态。
 *
 * <p>校验顺序（每一步失败都抛出携带 {@link LoadFailureReason} 的 {@link ExtensionLoadingException}）：
 * <ol>
 *   <li>重复声明</li>
 *   <li>实现类存在且可赋值给 {@link PluginExtension}</li>
 *   <li>实现类可实例化（非抽象/接口、存在公开无参构造、构造不抛异常）</li>
 *   <li>框架最低版本兼容</li>
 *   <li>依赖存在且最低版本满足</li>
 *   <li>无循环依赖</li>
 * </ol>
 */
public class ExtensionLoader {

    private static final Logger log = LoggerFactory.getLogger(ExtensionLoader.class);

    private final Version frameworkVersion;

    public ExtensionLoader(Version frameworkVersion) {
        this.frameworkVersion = frameworkVersion;
    }

    /**
     * 加载声明的扩展集合。
     *
     * @param declarations 配置中的扩展声明
     * @return 顺序稳定的不可变快照（被依赖者在前；随后按 priority 升序、name 升序）
     * @throws ExtensionLoadingException 任一扩展被拒绝时抛出，异常原因可区分、冲突对象明确
     */
    public ExtensionSnapshot load(List<ExtensionDeclaration> declarations) {
        List<ExtensionDeclaration> input = declarations == null ? List.of() : declarations;
        log.info("开始加载扩展，框架版本={}，声明数量={}，输入配置={}", frameworkVersion, input.size(), describe(input));

        Map<String, ExtensionDeclaration> byName = validateUniqueNames(input);
        Map<String, PluginExtension> instances = instantiateAll(byName);
        validateFrameworkCompatibility(byName);
        Map<String, List<ExtensionRequirement>> requirements = validateDependencies(byName);
        List<String> ordered = topologicalOrder(byName, requirements);

        List<LoadedExtension> loaded = new ArrayList<>(ordered.size());
        for (String name : ordered) {
            ExtensionDeclaration d = byName.get(name);
            loaded.add(new LoadedExtension(name, d.getVersion(), d.getPriority(), instances.get(name)));
            log.info("扩展[{}]校验通过并加入快照: version={}, priority={}, order={}",
                    name, d.getVersion(), d.getPriority(), loaded.size());
        }
        ExtensionSnapshot snapshot = new ExtensionSnapshot(loaded, signature(input));
        log.info("扩展加载完成，最终启用顺序={}，快照签名={}", ordered, snapshot.signature());
        return snapshot;
    }

    /** 第 1 步：名称非空且唯一。 */
    private Map<String, ExtensionDeclaration> validateUniqueNames(List<ExtensionDeclaration> declarations) {
        Map<String, ExtensionDeclaration> byName = new LinkedHashMap<>();
        for (ExtensionDeclaration d : declarations) {
            String name = d.getName() == null ? null : d.getName().trim();
            if (name == null || name.isEmpty()) {
                throw new ExtensionLoadingException(LoadFailureReason.UNDECLARED_IMPLEMENTATION,
                        "扩展声明缺少名称(name)，原始声明=" + shortDescribe(d), null);
            }
            if (byName.containsKey(name)) {
                throw new ExtensionLoadingException(LoadFailureReason.DUPLICATE_DECLARATION,
                        "扩展[" + name + "]被重复声明，重复实现=" + d.getImplementation(), name);
            }
            byName.put(name, d);
        }
        log.debug("名称唯一性校验通过: {}", byName.keySet());
        return byName;
    }

    /** 第 2、3 步：实现类存在、类型正确且可实例化。先统一校验类型，再统一实例化。 */
    private Map<String, PluginExtension> instantiateAll(Map<String, ExtensionDeclaration> byName) {
        Map<String, Class<? extends PluginExtension>> classes = new HashMap<>();
        for (Map.Entry<String, ExtensionDeclaration> e : byName.entrySet()) {
            classes.put(e.getKey(), resolveImplementation(e.getKey(), e.getValue()));
        }
        Map<String, PluginExtension> instances = new LinkedHashMap<>();
        for (Map.Entry<String, ExtensionDeclaration> e : byName.entrySet()) {
            String name = e.getKey();
            Class<? extends PluginExtension> type = classes.get(name);
            int modifiers = type.getModifiers();
            if (Modifier.isAbstract(modifiers) || Modifier.isInterface(modifiers)
                    || type.isAnonymousClass() || type.isMemberClass() && !Modifier.isStatic(modifiers)) {
                throw new ExtensionLoadingException(LoadFailureReason.UNINSTANTIABLE_IMPLEMENTATION,
                        "扩展[" + name + "]的实现类无法实例化（抽象类/接口/非静态内部类）: " + type.getName(), name);
            }
            Constructor<? extends PluginExtension> constructor;
            try {
                constructor = type.getConstructor();
            } catch (NoSuchMethodException ex) {
                throw new ExtensionLoadingException(LoadFailureReason.UNINSTANTIABLE_IMPLEMENTATION,
                        "扩展[" + name + "]的实现类缺少公开无参构造方法: " + type.getName(), name);
            }
            try {
                PluginExtension instance = constructor.newInstance();
                instances.put(name, instance);
                log.debug("扩展[{}]实例化成功: {}", name, type.getName());
            } catch (ReflectiveOperationException ex) {
                throw new ExtensionLoadingException(LoadFailureReason.UNINSTANTIABLE_IMPLEMENTATION,
                        "扩展[" + name + "]的实现类构造失败: " + type.getName()
                                + "，原因=" + rootMessage(ex), name);
            }
        }
        return instances;
    }

    @SuppressWarnings("unchecked")
    private Class<? extends PluginExtension> resolveImplementation(String name, ExtensionDeclaration d) {
        String className = d.getImplementation();
        if (className == null || className.isBlank()) {
            throw new ExtensionLoadingException(LoadFailureReason.UNDECLARED_IMPLEMENTATION,
                    "扩展[" + name + "]未声明实现类(implementation)", name);
        }
        Class<?> type;
        try {
            type = Class.forName(className.trim(), false, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException ex) {
            try {
                type = Class.forName(className.trim());
            } catch (ClassNotFoundException e) {
                throw new ExtensionLoadingException(LoadFailureReason.UNDECLARED_IMPLEMENTATION,
                        "扩展[" + name + "]声明的实现类不存在: " + className, name);
            }
        }
        if (!PluginExtension.class.isAssignableFrom(type)) {
            throw new ExtensionLoadingException(LoadFailureReason.UNDECLARED_IMPLEMENTATION,
                    "扩展[" + name + "]声明的实现类未实现 " + PluginExtension.class.getName() + ": " + type.getName(),
                    name);
        }
        return (Class<? extends PluginExtension>) type;
    }

    /** 第 4 步：框架最低版本兼容性。 */
    private void validateFrameworkCompatibility(Map<String, ExtensionDeclaration> byName) {
        for (Map.Entry<String, ExtensionDeclaration> e : byName.entrySet()) {
            String requiredText = e.getValue().getMinFrameworkVersion();
            if (requiredText == null || requiredText.isBlank()) {
                continue;
            }
            Version required = Version.parse(requiredText);
            if (!frameworkVersion.satisfies(required)) {
                throw new ExtensionLoadingException(LoadFailureReason.FRAMEWORK_VERSION_NOT_SATISFIED,
                        "扩展[" + e.getKey() + "]要求最低框架版本 " + required
                                + "，当前框架版本 " + frameworkVersion, e.getKey());
            }
            log.debug("扩展[{}]框架版本兼容: 要求>={}, 当前={}", e.getKey(), required, frameworkVersion);
        }
    }

    /** 第 5 步：依赖存在且版本满足。 */
    private Map<String, List<ExtensionRequirement>> validateDependencies(
            Map<String, ExtensionDeclaration> byName) {
        Map<String, List<ExtensionRequirement>> requirements = new LinkedHashMap<>();
        for (Map.Entry<String, ExtensionDeclaration> e : byName.entrySet()) {
            String name = e.getKey();
            List<ExtensionRequirement> parsed = new ArrayList<>();
            List<String> raw = e.getValue().getRequires();
            if (raw != null) {
                for (String spec : raw) {
                    ExtensionRequirement req = parseRequirement(spec);
                    if (!byName.containsKey(req.name())) {
                        throw new ExtensionLoadingException(LoadFailureReason.MISSING_DEPENDENCY,
                                "扩展[" + name + "]依赖的扩展[" + req.name() + "]未在配置中声明启用",
                                name + "->" + req.name());
                    }
                    Version dependencyVersion = Version.parse(byName.get(req.name()).getVersion());
                    if (!dependencyVersion.satisfies(req.minVersion())) {
                        throw new ExtensionLoadingException(LoadFailureReason.DEPENDENCY_VERSION_NOT_SATISFIED,
                                "扩展[" + name + "]要求依赖[" + req.name() + "]最低版本 " + req.minVersion()
                                        + "，实际版本 " + dependencyVersion,
                                name + "->" + req.name() + "@" + dependencyVersion);
                    }
                    parsed.add(req);
                    log.debug("扩展[{}]依赖[{}]版本满足: 要求>={}, 实际={}",
                            name, req.name(), req.minVersion(), dependencyVersion);
                }
            }
            requirements.put(name, parsed);
        }
        return requirements;
    }

    private ExtensionRequirement parseRequirement(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new ExtensionLoadingException(LoadFailureReason.MISSING_DEPENDENCY,
                    "存在空白的依赖声明", null);
        }
        String trimmed = spec.trim();
        int idx = trimmed.indexOf(':');
        String depName = (idx < 0 ? trimmed : trimmed.substring(0, idx)).trim();
        String minVersion = idx < 0 ? "0.0.0" : trimmed.substring(idx + 1).trim();
        if (depName.isEmpty()) {
            throw new ExtensionLoadingException(LoadFailureReason.MISSING_DEPENDENCY,
                    "依赖声明缺少扩展名称: '" + spec + "'", null);
        }
        return new ExtensionRequirement(depName, Version.parse(minVersion));
    }

    /**
     * 第 6 步 + 稳定排序：Kahn 拓扑排序，被依赖者先输出；
     * 同一时刻可输出的节点按 (priority 升序, name 升序) 选取，保证结果稳定可复现。
     * 若最终未能全部输出，则存在环，通过 DFS 找出具体环路后抛出。
     */
    private List<String> topologicalOrder(Map<String, ExtensionDeclaration> byName,
                                          Map<String, List<ExtensionRequirement>> requirements) {
        Map<String, TreeSet<String>> dependents = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        for (String name : byName.keySet()) {
            dependents.put(name, new TreeSet<>());
            indegree.put(name, 0);
        }
        for (Map.Entry<String, List<ExtensionRequirement>> e : requirements.entrySet()) {
            String dependent = e.getKey();
            for (ExtensionRequirement req : e.getValue()) {
                if (dependents.get(req.name()).add(dependent)) {
                    indegree.merge(dependent, 1, Integer::sum);
                }
            }
        }
        java.util.PriorityQueue<String> ready = new java.util.PriorityQueue<>(
                Comparator.comparingInt((String n) -> byName.get(n).getPriority()).thenComparing(n -> n));
        indegree.forEach((n, deg) -> {
            if (deg == 0) {
                ready.add(n);
            }
        });
        List<String> ordered = new ArrayList<>(byName.size());
        while (!ready.isEmpty()) {
            String current = ready.poll();
            ordered.add(current);
            for (String next : dependents.get(current)) {
                if (indegree.merge(next, -1, Integer::sum) == 0) {
                    ready.add(next);
                }
            }
        }
        if (ordered.size() != byName.size()) {
            String cycle = findCycle(byName.keySet(), requirements);
            throw new ExtensionLoadingException(LoadFailureReason.CIRCULAR_DEPENDENCY,
                    "检测到循环依赖，环路: " + cycle, cycle);
        }
        log.debug("拓扑排序结果: {}", ordered);
        return ordered;
    }

    /** DFS 找出一条具体环路，形如 a -> b -> a。 */
    private String findCycle(java.util.Set<String> names,
                             Map<String, List<ExtensionRequirement>> requirements) {
        Map<String, Integer> color = new HashMap<>();
        List<String> stack = new ArrayList<>();
        for (String start : new TreeSet<>(names)) {
            String cycle = dfsCycle(start, requirements, color, stack);
            if (cycle != null) {
                return cycle;
            }
        }
        return "未知环路";
    }

    private String dfsCycle(String node, Map<String, List<ExtensionRequirement>> requirements,
                            Map<String, Integer> color, List<String> stack) {
        int state = color.getOrDefault(node, 0);
        if (state == 1) {
            int from = stack.indexOf(node);
            List<String> cycleNodes = new ArrayList<>(stack.subList(from, stack.size()));
            cycleNodes.add(node);
            return String.join(" -> ", cycleNodes);
        }
        if (state == 2) {
            return null;
        }
        color.put(node, 1);
        stack.add(node);
        for (ExtensionRequirement req : requirements.getOrDefault(node, List.of())) {
            if (!requirements.containsKey(req.name())) {
                continue;
            }
            String cycle = dfsCycle(req.name(), requirements, color, stack);
            if (cycle != null) {
                return cycle;
            }
        }
        stack.remove(stack.size() - 1);
        color.put(node, 2);
        return null;
    }

    /**
     * 计算配置签名：按 name 排序后对规范化内容取 SHA-256。
     * 只与配置内容有关，与声明书写顺序无关，用于刷新幂等判定。
     */
    private String signature(List<ExtensionDeclaration> declarations) {
        List<ExtensionDeclaration> sorted = new ArrayList<>(declarations);
        sorted.sort(Comparator.comparing(d -> d.getName() == null ? "" : d.getName().trim()));
        StringBuilder sb = new StringBuilder();
        for (ExtensionDeclaration d : sorted) {
            List<String> requires = d.getRequires() == null ? List.of() : d.getRequires();
            sb.append(d.getName()).append('|').append(d.getImplementation()).append('|')
              .append(d.getVersion()).append('|').append(d.getPriority()).append('|')
              .append(d.getMinFrameworkVersion()).append('|')
              .append(String.join(",", new TreeSet<>(requires))).append('\n');
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("无法计算配置签名", ex);
        }
    }

    private String describe(List<ExtensionDeclaration> declarations) {
        List<String> parts = new ArrayList<>();
        for (ExtensionDeclaration d : declarations) {
            parts.add(shortDescribe(d));
        }
        return parts.toString();
    }

    private String shortDescribe(ExtensionDeclaration d) {
        if (d == null) {
            return "null";
        }
        return "{name=" + d.getName() + ", impl=" + d.getImplementation() + ", version=" + d.getVersion()
                + ", priority=" + d.getPriority() + ", requires=" + d.getRequires()
                + ", minFramework=" + d.getMinFrameworkVersion() + "}";
    }

    private String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}
