package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 启动时按配置加载初始扩展集合。初始配置不合法时直接抛出，使应用启动失败，
 * 避免“服务已启动但扩展集合不可用”的歧义状态。
 */
public class ExtensionInitializer {

    private static final Logger log = LoggerFactory.getLogger(ExtensionInitializer.class);

    private final ExtensionRegistry registry;
    private final ExtensionProperties properties;

    public ExtensionInitializer(ExtensionRegistry registry, ExtensionProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    @PostConstruct
    public void initialize() {
        log.info("启动加载扩展配置: frameworkVersion={}, 声明={}",
                properties.getFrameworkVersion(), properties.getExtensions());
        try {
            ExtensionRegistry.RefreshResult result = registry.refresh(properties.getExtensions());
            log.info("启动扩展加载完成: changed={}, 启用数量={}",
                    result.changed(), result.snapshot().extensions().size());
        } catch (ExtensionLoadingException ex) {
            log.error("启动扩展加载被拒绝: reason={}, conflictTarget={}, message={}",
                    ex.reason(), ex.conflictTarget(), ex.getMessage());
            throw ex;
        }
    }
}
