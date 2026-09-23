package com.github.xingjianluo.springbootpluginscaffoldjava.demo;

import com.github.xingjianluo.springbootpluginscaffoldjava.config.ExtensionRegistrar;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionRegistry;
import org.springframework.stereotype.Component;

/** 把脚手架自带的 demo 扩展登记到可用实现白名单。 */
@Component
public class DemoExtensionRegistrar implements ExtensionRegistrar {

    @Override
    public void register(ExtensionRegistry registry) {
        registry.register(DemoCoreExtension.class);
        registry.register(DemoFeatureExtension.class);
    }
}
