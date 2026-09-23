package com.github.xingjianluo.springbootpluginscaffoldjava.tests;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionManager;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端装配验证：registrar 白名单 + properties 声明 + 启动加载 + 依赖排序。
 * 输入（application.properties: extension.*）与判定依据打印到日志。
 */
@SpringBootTest
class ExtensionWiringIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ExtensionWiringIntegrationTest.class);

    @Autowired
    private ExtensionManager extensionManager;

    @Autowired
    private ExtensionRegistry extensionRegistry;

    @Test
    void startupLoadsConfiguredDemoExtensionsInDependencyOrder() {
        log.info("input config from application.properties: fw=1.2.0, enabled=[DemoFeature, DemoCore]; "
                + "basis=registry whitelist + dependency order (demo-core first)");

        List<String> ids = extensionManager.current().ids();
        log.info("decision: active ids={}, registry={}", ids,
                extensionRegistry.all().stream().map(Class::getSimpleName).toList());

        assertEquals(List.of("demo-core", "demo-feature"), ids);
        assertTrue(extensionRegistry.all().size() >= 2);
        assertEquals("1.2.0", extensionManager.current().configuration().frameworkVersion());
    }
}
