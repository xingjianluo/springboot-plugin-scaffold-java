package com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.Extension;

import java.util.Set;

/** 循环依赖一端：cycle-one -> cycle-two。 */
public class CycleOneExtension implements Extension {

    public static final String ID = "cycle-one";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<String> requiredExtensions() {
        return Set.of(CycleTwoExtension.ID);
    }
}
