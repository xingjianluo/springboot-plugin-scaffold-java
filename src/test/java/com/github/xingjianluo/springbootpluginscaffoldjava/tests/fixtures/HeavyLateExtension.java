package com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.Extension;

/**
 * 激活顺序最靠后（优先级 200）的重资源扩展：
 * 在“排它前面的扩展激活失败”场景中，它会被构造但来不及激活，
 * 用于验证这类实例也会被回收而不是白白占用资源。
 */
public class HeavyLateExtension implements Extension {

    public static final String ID = "heavy-late";

    public HeavyLateExtension() {
        ResourceLifecycleTracker.recordCreated(getClass());
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int priority() {
        return 200;
    }

    @Override
    public void activate() {
        ResourceLifecycleTracker.recordActivated(getClass());
    }

    @Override
    public void deactivate() {
        ResourceLifecycleTracker.recordDeactivated(getClass());
    }
}
