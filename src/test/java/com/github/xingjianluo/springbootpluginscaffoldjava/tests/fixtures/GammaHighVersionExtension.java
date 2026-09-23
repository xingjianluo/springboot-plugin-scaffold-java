package com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.Extension;

/** 要求框架最低 9.0.0，用于兼容性拒绝测试。 */
public class GammaHighVersionExtension implements Extension {

    public static final String ID = "gamma-high";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String requiredFrameworkVersion() {
        return "9.0.0";
    }
}
