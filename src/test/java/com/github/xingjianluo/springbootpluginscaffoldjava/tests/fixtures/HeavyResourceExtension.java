package com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.Extension;

/**
 * 模拟带重资源初始化（连接池/缓存/后台线程）的独立扩展：
 * 构造即“开机”，创建次数由 {@link ResourceLifecycleTracker} 统计。
 * 用于验证与它无关的配置变化绝不会让它再初始化一次。
 */
public class HeavyResourceExtension implements Extension {

    public static final String ID = "heavy-resource";

    public HeavyResourceExtension() {
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
