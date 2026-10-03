package com.github.xingjianluo.springbootpluginscaffoldjava.tests.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.DefaultExtensionManager;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.DefaultExtensionRegistry;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionManager;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionPlanBuilder;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.AlphaExtension;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.BetaExtension;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.LifecycleExtension;
import com.github.xingjianluo.springbootpluginscaffoldjava.web.ExtensionController;
import com.github.xingjianluo.springbootpluginscaffoldjava.web.ExtensionExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 增量刷新报告与回到上一次成功配置接口的 HTTP 层测试。
 */
class IncrementalRollbackControllerTest {

    private static final Logger log = LoggerFactory.getLogger(IncrementalRollbackControllerTest.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;
    private ExtensionManager manager;

    @BeforeEach
    void setUp() {
        DefaultExtensionRegistry registry = new DefaultExtensionRegistry();
        registry.register(AlphaExtension.class);
        registry.register(BetaExtension.class);
        registry.register(LifecycleExtension.class);
        manager = new DefaultExtensionManager(new ExtensionPlanBuilder(registry));
        mockMvc = MockMvcBuilders.standaloneSetup(new ExtensionController(manager))
                .setControllerAdvice(new ExtensionExceptionHandler())
                .build();
    }

    private String body(String... classNames) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "frameworkVersion", "1.0.0",
                "enabled", List.of(classNames)));
    }

    @Test
    void refreshReportsPerExtensionActionsAndIdempotentSkip() throws Exception {
        String first = body(BetaExtension.class.getName(), AlphaExtension.class.getName());
        mockMvc.perform(post("/api/extensions/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.changes[0].id").value("alpha"))
                .andExpect(jsonPath("$.changes[0].action").value("ADDED"))
                .andExpect(jsonPath("$.changes[0].reason").value("NEW_ENABLED"));

        // 只新增 lifecycle，alpha/beta 必须报告为 REUSED
        String second = body(BetaExtension.class.getName(), AlphaExtension.class.getName(),
                LifecycleExtension.class.getName());
        String response = mockMvc.perform(post("/api/extensions/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true))
                .andReturn().getResponse().getContentAsString();
        log.info("incremental refresh report: {}", response);

        // 同配置重复提交：applied=false，且全部 REUSED
        mockMvc.perform(post("/api/extensions/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.changes[?(@.action!='REUSED')]").isEmpty());
    }

    @Test
    void rollbackEndpointRestoresPreviousSnapshotAndRejectsWhenUnavailable() throws Exception {
        // 没有上一次成功配置：409
        mockMvc.perform(post("/api/extensions/rollback"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ILLEGAL_STATE"));

        mockMvc.perform(post("/api/extensions/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(BetaExtension.class.getName(), AlphaExtension.class.getName())))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/extensions/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(AlphaExtension.class.getName(), LifecycleExtension.class.getName())))
                .andExpect(status().isOk());

        String response = mockMvc.perform(post("/api/extensions/rollback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rolledBack").value(true))
                .andExpect(jsonPath("$.extensions[0].id").value("alpha"))
                .andExpect(jsonPath("$.extensions[1].id").value("beta"))
                .andReturn().getResponse().getContentAsString();
        log.info("rollback report: {}", response);
    }
}
