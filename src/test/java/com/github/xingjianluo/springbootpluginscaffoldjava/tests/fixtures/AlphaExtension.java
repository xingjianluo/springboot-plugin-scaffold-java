package com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.Extension;

/** 无依赖基础扩展。 */
public class AlphaExtension implements Extension {

    public static final String ID = "alpha";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int priority() {
        return 100;
    }
}
