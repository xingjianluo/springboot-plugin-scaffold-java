package com.github.xingjianluo.springbootpluginscaffoldjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端集成测试：启动按配置加载、查询、幂等刷新、失败刷新返回可区分原因且旧状态不变。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "plugin.framework-version=1.0.0",
        "plugin.extensions[0].name=alpha",
        "plugin.extensions[0].implementation=com.github.xingjianluo.springbootpluginscaffoldjava.tests.ext.AlphaExtension",
        "plugin.extensions[0].version=1.2.0",
        "plugin.extensions[0].priority=10",
        "plugin.extensions[1].name=beta",
        "plugin.extensions[1].implementation=com.github.xingjianluo.springbootpluginscaffoldjava.tests.ext.BetaExtension",
        "plugin.extensions[1].version=2.0.0",
        "plugin.extensions[1].priority=20",
        "plugin.extensions[1].requires[0]=alpha:1.0.0"
})
class ExtensionIntegrationTests {

    private static final Logger log = LoggerFactory.getLogger(ExtensionIntegrationTests.class);
    private static final String PKG =
            "com.github.xingjianluo.springbootpluginscaffoldjava.tests.ext.";

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("启动加载：GET /api/extensions 返回配置中的 alpha,beta，顺序稳定")
    void startupLoadedAndQueryable() throws Exception {
        log.info("用例[启动加载] 输入配置=alpha(1.2.0,p10), beta(2.0.0,p20,requires alpha>=1.0.0)，判定依据=启动初始化器刷新成功");
        mockMvc.perform(get("/api/extensions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.extensions.length()").value(2))
                .andExpect(jsonPath("$.extensions[0].name").value("alpha"))
                .andExpect(jsonPath("$.extensions[1].name").value("beta"));
    }

    @Test
    @DisplayName("重复提交相同配置刷新幂等：changed=false，签名不变")
    void refreshIdempotentOverHttp() throws Exception {
        MvcResult first = mockMvc.perform(get("/api/extensions")).andReturn();
        String signature = json.readTree(first.getResponse().getContentAsString()).get("signature").asText();
        String body = json.writeValueAsString(Map.of("extensions", List.of(
                extension("alpha", "AlphaExtension", "1.2.0", 10, List.of()),
                extension("beta", "BetaExtension", "2.0.0", 20, List.of("alpha:1.0.0")))));
        log.info("用例[HTTP幂等刷新] 当前签名={}，重复提交相同配置，期望 changed=false", signature);
        mockMvc.perform(post("/api/extensions/refresh").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(false))
                .andExpect(jsonPath("$.signature").value(signature));
    }

    @Test
    @DisplayName("非法刷新返回 422 且原因可区分，旧集合保持不变（整体回退）")
    void invalidRefreshReturnsReasonAndKeepsOldState() throws Exception {
        String badBody = json.writeValueAsString(Map.of("extensions", List.of(
                extension("beta", "BetaExtension", "2.0.0", 20, List.of("missing-dep:1.0.0")))));
        log.info("用例[HTTP失败回退] 提交缺失依赖配置 beta->missing-dep，期望 422/MISSING_DEPENDENCY 且旧 alpha,beta 不变");
        mockMvc.perform(post("/api/extensions/refresh").contentType(MediaType.APPLICATION_JSON).content(badBody))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("MISSING_DEPENDENCY"))
                .andExpect(jsonPath("$.conflictTarget").value("beta->missing-dep"));
        mockMvc.perform(get("/api/extensions"))
                .andExpect(jsonPath("$.extensions.length()").value(2))
                .andExpect(jsonPath("$.extensions[0].name").value("alpha"));
    }

    @Test
    @DisplayName("重复声明刷新返回 422，原因为 DUPLICATE_DECLARATION")
    void duplicateRefreshRejected() throws Exception {
        String body = json.writeValueAsString(Map.of("extensions", List.of(
                extension("alpha", "AlphaExtension", "1.0.0", 10, List.of()),
                extension("alpha", "LegacyExtension", "1.0.0", 20, List.of()))));
        log.info("用例[HTTP重复声明] 提交两个同名 alpha，期望 422/DUPLICATE_DECLARATION");
        mockMvc.perform(post("/api/extensions/refresh").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("DUPLICATE_DECLARATION"))
                .andExpect(jsonPath("$.conflictTarget").value("alpha"));
    }

    private Map<String, Object> extension(String name, String impl, String version,
                                          int priority, List<String> requires) {
        return Map.of("name", name, "implementation", PKG + impl, "version", version,
                "priority", priority, "requires", requires);
    }
}
