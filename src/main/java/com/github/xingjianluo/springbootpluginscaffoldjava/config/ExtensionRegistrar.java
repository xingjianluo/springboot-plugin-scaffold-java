package com.github.xingjianluo.springbootpluginscaffoldjava.config;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.Extension;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionRegistry;

/**
 * 扩展注册回调：各使用方实现本接口把自己的扩展类登记进白名单。
 */
@FunctionalInterface
public interface ExtensionRegistrar {

    void register(ExtensionRegistry registry);
}
