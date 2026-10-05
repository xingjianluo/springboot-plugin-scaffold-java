package com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.Extension;

import java.util.Set;

/** 依赖 heavy-core 的重资源扩展：core 被替换时它必须连带重建（且只重建一次）。 */
public class HeavyConsumerExtension implements Extension {

    public static final String ID = "heavy-consumer";

    public HeavyConsumerExtension() {
        ResourceLifecycleTracker.recordCreated(getClass());
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int priority() {
        return 50;
    }

    @Override
    public Set<String> requiredExtensions() {
        return Set.of(HeavyCoreExtension.ID);
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
