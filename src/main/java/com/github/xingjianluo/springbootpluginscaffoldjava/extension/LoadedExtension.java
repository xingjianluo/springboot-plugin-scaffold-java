package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

/**
 * 成功加载后的扩展实例及其元数据（不可变）。
 */
public record LoadedExtension(String name,
                              String version,
                              int priority,
                              PluginExtension instance) {
}
