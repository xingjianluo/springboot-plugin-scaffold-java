package com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.Extension;

import java.util.Set;

/**
 * 与 {@link InstanceTrackingExtension} 同 id ("tracker") 的另一实现：
 * 实现替换的同时新增了对 alpha 的依赖边。
 */
public class TrackerAlphaDependentExtension implements Extension {

    public static final String ID = InstanceTrackingExtension.ID;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<String> requiredExtensions() {
        return Set.of(AlphaExtension.ID);
    }
}
