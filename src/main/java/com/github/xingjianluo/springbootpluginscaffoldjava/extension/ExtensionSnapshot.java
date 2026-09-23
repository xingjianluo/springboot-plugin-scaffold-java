package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

import java.util.List;

/**
 * 某一时刻已启用扩展集合的不可变快照，加载顺序稳定可复现。
 */
public record ExtensionSnapshot(List<LoadedExtension> extensions, String signature) {

    public ExtensionSnapshot {
        extensions = List.copyOf(extensions);
    }
}
