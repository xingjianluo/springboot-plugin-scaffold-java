package com.github.xingjianluo.springbootpluginscaffoldjava.demo;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 示例基础扩展：无依赖、无额外框架版本要求，可被其他扩展依赖。 */
public class DemoCoreExtension implements Extension {

    private static final Logger log = LoggerFactory.getLogger(DemoCoreExtension.class);

    public static final String ID = "demo-core";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public void activate() {
        log.info("demo-core activated");
    }

    @Override
    public void deactivate() {
        log.info("demo-core deactivated");
    }
}
