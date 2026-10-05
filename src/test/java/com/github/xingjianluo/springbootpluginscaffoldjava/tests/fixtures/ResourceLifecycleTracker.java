package com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 统计每个扩展实现类的 构造/激活/停用 次数，
 * 用于断言“未变化的扩展从头到尾只被创建一次”以及“被拒绝的提交不留孤儿实例”。
 */
public final class ResourceLifecycleTracker {

    private static final Map<String, AtomicInteger> CREATED = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> ACTIVATED = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> DEACTIVATED = new ConcurrentHashMap<>();

    private ResourceLifecycleTracker() {
    }

    public static void recordCreated(Class<?> type) {
        counter(CREATED, type).incrementAndGet();
    }

    public static void recordActivated(Class<?> type) {
        counter(ACTIVATED, type).incrementAndGet();
    }

    public static void recordDeactivated(Class<?> type) {
        counter(DEACTIVATED, type).incrementAndGet();
    }

    public static int created(Class<?> type) {
        return counter(CREATED, type).get();
    }

    public static int activated(Class<?> type) {
        return counter(ACTIVATED, type).get();
    }

    public static int deactivated(Class<?> type) {
        return counter(DEACTIVATED, type).get();
    }

    /** 仍在“存活”的实例数：被构造但尚未被停用的数量。 */
    public static int live(Class<?> type) {
        return created(type) - deactivated(type);
    }

    public static void reset() {
        CREATED.clear();
        ACTIVATED.clear();
        DEACTIVATED.clear();
    }

    public static String describe(Class<?> type) {
        return type.getSimpleName() + "{created=" + created(type)
                + ", activated=" + activated(type)
                + ", deactivated=" + deactivated(type) + "}";
    }

    private static AtomicInteger counter(Map<String, AtomicInteger> map, Class<?> type) {
        return map.computeIfAbsent(type.getName(), k -> new AtomicInteger());
    }
}
