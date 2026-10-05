package com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.Extension;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * activate() 必然失败的重资源扩展，统计创建与回收次数。
 * 用于验证：激活失败整体拒绝后，这个“建出来却没启用”的实例会被 deactivate() 回收。
 */
public class HeavyActivateFailingExtension implements Extension {

    public static final String ID = "heavy-failing";

    public static final AtomicInteger CREATIONS = new AtomicInteger();
    public static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    public HeavyActivateFailingExtension() {
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
        throw new IllegalStateException("boom from heavy failing activate()");
    }

    @Override
    public void deactivate() {
        EVENTS.add("deactivate:" + System.identityHashCode(this));
    }
}
