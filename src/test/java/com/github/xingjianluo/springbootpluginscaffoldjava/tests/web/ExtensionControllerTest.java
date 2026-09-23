package com.github.xingjianluo.springbootpluginscaffoldjava.tests.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.DefaultExtensionManager;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.DefaultExtensionRegistry;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionManager;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionPlanBuilder;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.AlphaExtension;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.BetaExtension;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 层测试：输入 JSON 配置与判定依据（拒绝原因）打印到日志。
 */
class ExtensionControllerTest {

    private static final Logger log = LoggerFactory.getLogger(ExtensionControllerTest.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;
    private ExtensionManager manager;

    @BeforeEach
    void setUp() {
        DefaultExtensionRegistry registry = new DefaultExtensionRegistry();
        registry.register(AlphaExtension.class);
        registry.register(BetaExtension.class);
        manager = new DefaultExtensionManager(new ExtensionPlanBuilder(registry));
        mockMvc = MockMvcBuilders.standaloneSetup(new ExtensionController(manager))
                .setControllerAdvice(new ExtensionExceptionHandler())
                .build();
    }

    @Test
    void getReturnsCurrentSnapshot() throws Exception {
        log.info("input: GET /api/extensions on empty startup; basis=immutable snapshot");
        mockMvc.perform(get("/api/extensions"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"frameworkVersion\":\"0.0.0\",\"extensions\":[]}"));
    }

    @Test
    void refreshReturnsOrderedSnapshot() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "frameworkVersion", "1.0.0",
                "enabled", List.of(BetaExtension.class.getName(), AlphaExtension.class.getName())));
        log.info("input: POST /refresh body={}; basis=dependency alpha must precede beta", body);

        mockMvc.perform(post("/api/extensions/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.extensions[0].id").value("alpha"))
                .andExpect(jsonPath("$.extensions[1].id").value("beta"));

        mockMvc.perform(get("/api/extensions"))
                .andExpect(jsonPath("$.extensions[0].id").value("alpha"));
    }

    @Test
    void rejectedRefreshReturns422WithDistinguishableReason() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "frameworkVersion", "1.0.0",
                "enabled", List.of("com.example.Unknown", BetaExtension.class.getName())));
        log.info("input: POST /refresh body={}; basis=unknown class + missing dependency alpha", body);

        String response = mockMvc.perform(post("/api/extensions/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("EXTENSION_LOAD_REJECTED"))
                .andReturn().getResponse().getContentAsString();
        log.info("decision response: {}", response);
    }

    @Test
    void invalidBodyReturns400() throws Exception {
        log.info("input: POST /refresh with empty body; basis=request body required");
        mockMvc.perform(post("/api/extensions/refresh").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }
}
