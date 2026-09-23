package com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.Extension;

import java.util.Set;

/** activate() 必然抛错，用于刷新整体回滚测试；可选择声明依赖。 */
public class ActivateFailingExtension implements Extension {

    public static final String ID = "activate-failing";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<String> requiredExtensions() {
        return Set.of(AlphaExtension.ID);
    }

    @Override
    public void activate() {
        throw new IllegalStateException("boom from activate()");
    }
}
