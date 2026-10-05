package com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.Extension;

/** 构造正常但 activate() 必然失败的重资源扩展，用于验证拒绝路径不留孤儿实例。 */
public class HeavyActivateFailingExtension implements Extension {

    public static final String ID = "heavy-failing";

    public HeavyActivateFailingExtension() {
        ResourceLifecycleTracker.recordCreated(getClass());
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public void activate() {
        ResourceLifecycleTracker.recordActivated(getClass());
        throw new IllegalStateException("boom from heavy activate()");
    }

    @Override
    public void deactivate() {
        ResourceLifecycleTracker.recordDeactivated(getClass());
    }
}
