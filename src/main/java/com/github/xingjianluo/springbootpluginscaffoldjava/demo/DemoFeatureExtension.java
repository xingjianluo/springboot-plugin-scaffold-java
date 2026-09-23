package com.github.xingjianluo.springbootpluginscaffoldjava.demo;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/** 示例业务扩展：依赖 demo-core，且要求框架版本不低于 1.0.0。 */
public class DemoFeatureExtension implements Extension {

    private static final Logger log = LoggerFactory.getLogger(DemoFeatureExtension.class);

    public static final String ID = "demo-feature";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String version() {
        return "1.2.0";
    }

    @Override
    public int priority() {
        return 20;
    }

    @Override
    public Set<String> requiredExtensions() {
        return Set.of(DemoCoreExtension.ID);
    }

    @Override
    public String requiredFrameworkVersion() {
        return "1.0.0";
    }

    @Override
    public void activate() {
        log.info("demo-feature activated");
    }

    @Override
    public void deactivate() {
        log.info("demo-feature deactivated");
    }
}
