package com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.Extension;

import java.util.Set;

/**
 * 与 {@link BetaExtension} 同 id ("beta") 的替代实现，但 activate() 必然失败。
 * 用于验证增量切换中途失败时整体回退、旧实例仍可用。
 */
public class BetaActivateFailingExtension implements Extension {

    public static final String ID = BetaExtension.ID;

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

    @Override
    public void activate() {
        throw new IllegalStateException("boom from incremental beta activate()");
    }
}
