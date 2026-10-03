package com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.Extension;

import java.util.Set;

/**
 * 依赖 {@link BetaExtension}（beta 又依赖 alpha），形成 alpha -> beta -> epsilon 链，
 * 用于验证换 alpha 实现时连带重建沿依赖链向上传播两跳。
 */
public class EpsilonExtension implements Extension {

    public static final String ID = "epsilon";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int priority() {
        return 60;
    }

    @Override
    public Set<String> requiredExtensions() {
        return Set.of(BetaExtension.ID);
    }
}
