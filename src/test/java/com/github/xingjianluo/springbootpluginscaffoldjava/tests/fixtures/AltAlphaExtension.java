package com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.Extension;

/**
 * 与 {@link AlphaExtension} 声明相同 id（"alpha"）的另一份实现类。
 * 配置里把 Alpha 换成它时，属于“同 id 换实现”，应判定为重建，
 * 且依赖 alpha 的扩展（如 beta）必须连带重建。
 */
public class AltAlphaExtension implements Extension {

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
