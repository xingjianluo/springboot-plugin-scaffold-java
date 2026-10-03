package com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.Extension;

/** 无依赖的独立扩展，用于在“只新增一个扩展”时度量增量开销。 */
public class DeltaExtension implements Extension {

    public static final String ID = "delta";

    @Override
    public String id() {
        return ID;
    }
}
