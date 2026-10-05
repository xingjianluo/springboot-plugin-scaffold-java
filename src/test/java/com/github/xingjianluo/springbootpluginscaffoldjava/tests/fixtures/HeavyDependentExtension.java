package com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.Extension;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 依赖 heavy 的扩展，统计创建次数。
 * 用于验证：heavy 换实现时本扩展被连带重建（创建 +1），其余场景一律不重建。
 */
public class HeavyDependentExtension implements Extension {

    public static final String ID = "heavy-dependent";

    public static final AtomicInteger CREATIONS = new AtomicInteger();
    public static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    public HeavyDependentExtension() {
        CREATIONS.incrementAndGet();
        EVENTS.add("create:" + System.identityHashCode(this));
    }

    public static void reset() {
        CREATIONS.set(0);
        EVENTS.clear();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<String> requiredExtensions() {
        return Set.of(HeavyResourceExtension.ID);
    }

    @Override
    public void activate() {
        EVENTS.add("activate:" + System.identityHashCode(this));
    }

    @Override
    public void deactivate() {
        EVENTS.add("deactivate:" + System.identityHashCode(this));
    }
}
