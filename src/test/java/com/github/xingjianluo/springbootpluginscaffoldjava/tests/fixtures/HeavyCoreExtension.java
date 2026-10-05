package com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.Extension;

/** 重资源核心扩展（V1 实现），被 heavy-consumer 依赖。 */
public class HeavyCoreExtension implements Extension {

    public static final String ID = "heavy-core";

    public HeavyCoreExtension() {
        ResourceLifecycleTracker.recordCreated(getClass());
    }

    @Override
    public String id() {
        return ID;
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
