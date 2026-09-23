package com.github.xingjianluo.springbootpluginscaffoldjava.config;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.DefaultExtensionManager;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.DefaultExtensionRegistry;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.Extension;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionConfiguration;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionDeclaration;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionManager;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionPlanBuilder;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 组装扩展注册中心、计划构建器、运行时管理器，
 * 并在应用启动时按 {@code extension.*} 配置完成首次（原子）加载。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ExtensionProperties.class)
public class ExtensionAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ExtensionAutoConfiguration.class);

    @Bean
    public ExtensionRegistry extensionRegistry(List<ExtensionRegistrar> registrars) {
        ExtensionRegistry registry = new DefaultExtensionRegistry();
        registrars.forEach(registrar -> {
            registrar.register(registry);
            log.info("extension registrar applied: {}", registrar.getClass().getName());
        });
        log.info("available extension implementations: {}",
                registry.all().stream().map(Class::getName).toList());
        return registry;
    }

    @Bean
    public ExtensionPlanBuilder extensionPlanBuilder(ExtensionRegistry registry) {
        return new ExtensionPlanBuilder(registry);
    }

    @Bean
    public ExtensionManager extensionManager(ExtensionPlanBuilder planBuilder) {
        return new DefaultExtensionManager(planBuilder);
    }

    /**
     * 启动时把配置文件中的声明转换为 {@link ExtensionConfiguration} 并执行首次刷新。
     * 首次加载失败将以明确原因终止启动，避免在未知扩展状态下对外服务。
     */
    @Bean
    public ApplicationRunner extensionStartupLoader(ExtensionProperties properties,
                                                    ExtensionManager extensionManager) {
        return args -> {
            List<ExtensionDeclaration> declarations = properties.getEnabled().stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(ExtensionDeclaration::of)
                    .toList();
            ExtensionConfiguration configuration =
                    ExtensionConfiguration.of(properties.getFrameworkVersion(), declarations);
            log.info("startup extension configuration: fingerprint={}, enabled={}",
                    configuration.fingerprint(),
                    declarations.stream().map(ExtensionDeclaration::className).toList());
            extensionManager.refresh(configuration);
            log.info("startup extension loading completed: ids={}",
                    extensionManager.current().ids());
        };
    }
}
