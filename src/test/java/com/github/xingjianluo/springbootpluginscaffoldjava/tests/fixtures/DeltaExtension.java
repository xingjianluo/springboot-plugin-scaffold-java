package com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.Extension;

/**
 * 与 alpha/beta/lifecycle 均无关的独立扩展。
 * 用于验证：新增一个跟它无关的扩展时，它必须沿用原实例，不被重建。
 */
public class DeltaExtension implements Extension {

    public static final String ID = "delta";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int priority() {
        return 70;
    }

    @Override
    public void activate() {
        LifecycleExtension.EVENTS.add("activate-delta:" + System.identityHashCode(this));
    }

    @Override
    public void deactivate() {
        LifecycleExtension.EVENTS.add("deactivate-delta:" + System.identityHashCode(this));
    }
}
