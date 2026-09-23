package com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.Extension;

/** 缺少无参构造器，用于实例化失败测试。 */
public class NoNoArgConstructorExtension implements Extension {

    public NoNoArgConstructorExtension(String required) {
    }

    @Override
    public String id() {
        return "no-no-arg";
    }
}
