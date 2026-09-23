package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

/**
 * 对另一个扩展的依赖声明：依赖名称 + 最低可接受版本。
 */
public record ExtensionRequirement(String name, Version minVersion) {
}
