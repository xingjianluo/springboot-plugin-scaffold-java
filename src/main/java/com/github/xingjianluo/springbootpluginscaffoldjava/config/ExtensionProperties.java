package com.github.xingjianluo.springbootpluginscaffoldjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * extension.framework-version  当前框架版本
 * extension.enabled            启用的扩展实现类全名（逗号分隔或 YAML 列表）
 */
@ConfigurationProperties(prefix = "extension")
public class ExtensionProperties {

    private String frameworkVersion = "1.0.0";
    private List<String> enabled = new ArrayList<>();

    public String getFrameworkVersion() {
        return frameworkVersion;
    }

    public void setFrameworkVersion(String frameworkVersion) {
        this.frameworkVersion = frameworkVersion;
    }

    public List<String> getEnabled() {
        return enabled;
    }

    public void setEnabled(List<String> enabled) {
        this.enabled = enabled;
    }
}
