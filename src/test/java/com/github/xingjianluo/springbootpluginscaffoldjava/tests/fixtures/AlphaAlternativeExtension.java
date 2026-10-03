package com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.Extension;

/**
 * 与 {@link AlphaExtension} 声明同一个 id ("alpha") 的另一实现，
 * 用于验证“同一 id 换实现类”触发重建并连带其依赖方重建。
 */
public class AlphaAlternativeExtension implements Extension {

    public static final String ID = AlphaExtension.ID;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int priority() {
        return 100;
    }
}
