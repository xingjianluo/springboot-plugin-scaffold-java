package com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.Extension;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 模拟“重资源”扩展：构造即初始化连接池/缓存/后台线程（用创建计数代替），
 * 并记录每次 activate/deactivate 及实例身份。
 * 用于断言：未变化的扩展从头到尾只被创建一次、只保留运行中的那一份。
 */
public class HeavyResourceExtension implements Extension {

    public static final String ID = "heavy";

    /** 构造调用总次数（= 资源初始化次数）。 */
    public static final AtomicInteger CREATIONS = new AtomicInteger();

    /** 全局事件流：create/activate/deactivate:实例身份。 */
    public static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    public HeavyResourceExtension() {
        CREATIONS.incrementAndGet();
        EVENTS.add("create:" + System.identityHashCode(this));
    }

    public static void reset() {
        CREATIONS.set(0);
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
