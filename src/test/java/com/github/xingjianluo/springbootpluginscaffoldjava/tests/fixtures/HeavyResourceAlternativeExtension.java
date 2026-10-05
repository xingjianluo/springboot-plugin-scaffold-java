package com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.Extension;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 与 {@link HeavyResourceExtension} 同 id ("heavy") 的替代实现，
 * 用于“只换掉一个实现”场景；自身同样统计创建次数。
 */
public class HeavyResourceAlternativeExtension implements Extension {

    public static final String ID = HeavyResourceExtension.ID;

    public static final AtomicInteger CREATIONS = new AtomicInteger();
    public static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    public HeavyResourceAlternativeExtension() {
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
    public void activate() {
        EVENTS.add("activate:" + System.identityHashCode(this));
    }

    @Override
    public void deactivate() {
        EVENTS.add("deactivate:" + System.identityHashCode(this));
    }
}
