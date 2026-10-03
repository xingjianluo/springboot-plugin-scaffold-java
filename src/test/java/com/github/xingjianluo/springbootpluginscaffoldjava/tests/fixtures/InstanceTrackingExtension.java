package com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.Extension;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 无依赖扩展，并记录每次 activate/deactivate 及涉及的实例身份，
 * 用于验证增量刷新时的实例复用与“不停用、不重新激活”语义。
 */
public class InstanceTrackingExtension implements Extension {

    public static final String ID = "tracker";

    /** 全局事件流：动作:实例身份。 */
    public static final List<String> EVENTS = new CopyOnWriteArrayList<>();

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
