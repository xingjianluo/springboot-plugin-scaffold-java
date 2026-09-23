package com.github.xingjianluo.springbootpluginscaffoldjava.tests.ext;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.PluginExtension;

/** 没有公开无参构造，无法实例化。 */
public class PrivateConstructorExtension implements PluginExtension {
    private PrivateConstructorExtension() {
    }
}
