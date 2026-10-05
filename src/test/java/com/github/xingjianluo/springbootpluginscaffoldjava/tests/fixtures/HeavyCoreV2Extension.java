package com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.Extension;

/** 与 {@link HeavyCoreExtension} 同 id ("heavy-core") 的替换实现（V2）。 */
public class HeavyCoreV2Extension implements Extension {

    public static final String ID = HeavyCoreExtension.ID;

    public HeavyCoreV2Extension() {
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
