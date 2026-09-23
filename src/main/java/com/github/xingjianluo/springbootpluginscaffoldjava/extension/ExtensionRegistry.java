package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

import java.util.List;
import java.util.Optional;

/**
 * 可用扩展注册中心：维护允许被启用的实现类白名单。
 * 未在此注册的类名即属于“未声明的实现”。
 */
public interface ExtensionRegistry {

    void register(Class<? extends Extension> type);

    Optional<Class<? extends Extension>> findByClassName(String className);

    List<Class<? extends Extension>> all();
}
