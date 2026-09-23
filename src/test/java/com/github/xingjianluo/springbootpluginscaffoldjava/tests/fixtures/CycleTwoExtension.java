package com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.Extension;

import java.util.Set;

/** 循环依赖另一端：cycle-two -> cycle-one。 */
public class CycleTwoExtension implements Extension {

    public static final String ID = "cycle-two";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<String> requiredExtensions() {
        return Set.of(CycleOneExtension.ID);
    }
}
