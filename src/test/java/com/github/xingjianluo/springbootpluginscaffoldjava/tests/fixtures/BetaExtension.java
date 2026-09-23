package com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.Extension;

import java.util.Set;

/** 依赖 alpha 的扩展。 */
public class BetaExtension implements Extension {

    public static final String ID = "beta";

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
        return Set.of(AlphaExtension.ID);
    }
}
