package com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.Extension;

import java.util.ArrayList;
import java.util.List;

/** 记录 activate/deactivate 调用顺序，用于验证回滚与切换语义。 */
public class LifecycleExtension implements Extension {

    public static final String ID = "lifecycle";

    public static final List<String> EVENTS = new ArrayList<>();

    public static void reset() {
        EVENTS.clear();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void activate() {
        EVENTS.add("activate:" + System.identityHashCode(this));
    }

    @Override
    public void deactivate() {
        EVENTS.add("deactivate:" + System.identityHashCode(this));
    }
}
