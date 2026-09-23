package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code plugin.extensions.*} 配置绑定。
 */
@ConfigurationProperties(prefix = "plugin")
public class ExtensionProperties {

    /** 当前运行的框架版本，用于兼容性判定 */
    private String frameworkVersion = "1.0.0";

    /** 声明启用的扩展列表 */
    private List<ExtensionDeclaration> extensions = new ArrayList<>();

    public String getFrameworkVersion() {
        return frameworkVersion;
    }

    public void setFrameworkVersion(String frameworkVersion) {
        this.frameworkVersion = frameworkVersion;
    }

    public List<ExtensionDeclaration> getExtensions() {
        return extensions;
    }

    public void setExtensions(List<ExtensionDeclaration> extensions) {
        this.extensions = extensions;
    }
}
