package com.github.xingjianluo.springbootpluginscaffoldjava.tests.ext;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.PluginExtension;

/** 构造方法抛异常，无法实例化。 */
public class ThrowingExtension implements PluginExtension {
    public ThrowingExtension() {
        throw new IllegalStateException("boom");
    }
}
