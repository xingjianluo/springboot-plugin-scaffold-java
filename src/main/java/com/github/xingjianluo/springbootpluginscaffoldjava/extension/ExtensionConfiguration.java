package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 扩展能力的 Spring 装配。
 */
@Configuration
@EnableConfigurationProperties(ExtensionProperties.class)
public class ExtensionConfiguration {

    @Bean
    public ExtensionLoader extensionLoader(ExtensionProperties properties) {
        return new ExtensionLoader(Version.parse(properties.getFrameworkVersion()));
    }

    @Bean
    public ExtensionRegistry extensionRegistry(ExtensionLoader extensionLoader) {
        return new ExtensionRegistry(extensionLoader);
    }

    @Bean
    public ExtensionInitializer extensionInitializer(ExtensionRegistry registry,
                                                      ExtensionProperties properties) {
        return new ExtensionInitializer(registry, properties);
    }
}
