package com.github.xingjianluo.springbootpluginscaffoldjava.tests.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.DefaultExtensionManager;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.DefaultExtensionRegistry;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionManager;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionPlanBuilder;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.AlphaExtension;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.BetaExtension;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.DeltaExtension;
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
 * 增量热更新 HTTP 层：处理报告、幂等 skipped 标记、回退接口与 409 边界。
 */
class IncrementalExtensionControllerTest {

    private static final Logger log = LoggerFactory.getLogger(IncrementalExtensionControllerTest.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        DefaultExtensionRegistry registry = new DefaultExtensionRegistry();
        registry.register(AlphaExtension.class);
        registry.register(BetaExtension.class);
        registry.register(DeltaExtension.class);
        ExtensionManager manager = new DefaultExtensionManager(new ExtensionPlanBuilder(registry));
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
    void refreshReportExplainsActionsAndSecondSubmissionIsSkipped() throws Exception {
        String first = body(AlphaExtension.class.getName(), BetaExtension.class.getName());
        log.info("input #1: {}", first);
        mockMvc.perform(post("/api/extensions/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report.mode").value("REFRESH"))
                .andExpect(jsonPath("$.report.skipped").value(false))
                .andExpect(jsonPath("$.report.decisions[?(@.id=='alpha')].action").value(
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("ADDED"))))
                .andExpect(jsonPath("$.report.decisions[?(@.id=='beta')].action").value(
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("ADDED"))));

        // 再提交一份不同配置：新增 delta，alpha/beta 沿用
        String second = body(AlphaExtension.class.getName(), BetaExtension.class.getName(),
                DeltaExtension.class.getName());
        log.info("input #2 (add unrelated delta): {}", second);
        String response = mockMvc.perform(post("/api/extensions/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report.skipped").value(false))
                .andExpect(jsonPath("$.report.decisions[?(@.id=='delta')].action").value(
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("ADDED"))))
                .andExpect(jsonPath("$.report.decisions[?(@.id=='alpha')].action").value(
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("RETAINED"))))
                .andExpect(jsonPath("$.report.decisions[?(@.id=='beta')].action").value(
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("RETAINED"))))
                .andReturn().getResponse().getContentAsString();
        log.info("decision response #2: {}", response);

        // 完全相同的配置重复提交：skipped=true 且全部 RETAINED
        log.info("input #3 identical to #2, expected skipped");
        mockMvc.perform(post("/api/extensions/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report.skipped").value(true))
                .andExpect(jsonPath("$.report.decisions[0].action").value("RETAINED"));
    }

    @Test
    void restoreReturnsPreviousSnapshotReport() throws Exception {
        String cfgA = body(AlphaExtension.class.getName(), BetaExtension.class.getName());
        String cfgB = body(AlphaExtension.class.getName(), DeltaExtension.class.getName());
        mockMvc.perform(post("/api/extensions/refresh")
                .contentType(MediaType.APPLICATION_JSON).content(cfgA)).andExpect(status().isOk());
        mockMvc.perform(post("/api/extensions/refresh")
                .contentType(MediaType.APPLICATION_JSON).content(cfgB)).andExpect(status().isOk());
        log.info("two successful refreshes applied, now POST /restore");

        String response = mockMvc.perform(post("/api/extensions/restore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report.mode").value("RESTORE"))
                .andExpect(jsonPath("$.report.decisions[?(@.id=='beta')].action").value(
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("RESTORED"))))
                .andExpect(jsonPath("$.report.decisions[?(@.id=='delta')].action").value(
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("REMOVED"))))
                .andReturn().getResponse().getContentAsString();
        log.info("restore response: {}", response);
    }

    @Test
    void restoreWithoutHistoryReturns409() throws Exception {
        log.info("restore with no previous successful snapshot, expected 409");
        mockMvc.perform(post("/api/extensions/restore"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_STATE"));
    }
}
