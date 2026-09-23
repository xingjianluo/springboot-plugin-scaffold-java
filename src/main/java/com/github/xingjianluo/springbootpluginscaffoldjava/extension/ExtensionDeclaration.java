package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置中的一条扩展声明（可变 POJO，供 Spring Boot 绑定）。
 */
public class ExtensionDeclaration {

    /** 扩展唯一名，也是排序与依赖引用的标识 */
    private String name;

    /** 实现类全限定名 */
    private String implementation;

    /** 扩展自身版本，语义化版本号 */
    private String version = "1.0.0";

    /** 优先级，数值越小优先级越高 */
    private int priority = 100;

    /** 依赖列表，形如 {@code name:minVersion} */
    private List<String> requires = new ArrayList<>();

    /** 要求的最低框架版本 */
    private String minFrameworkVersion;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getImplementation() {
        return implementation;
    }

    public void setImplementation(String implementation) {
        this.implementation = implementation;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public List<String> getRequires() {
        return requires;
    }

    public void setRequires(List<String> requires) {
        this.requires = requires;
    }

    public String getMinFrameworkVersion() {
        return minFrameworkVersion;
    }

    public void setMinFrameworkVersion(String minFrameworkVersion) {
        this.minFrameworkVersion = minFrameworkVersion;
    }

    @Override
    public String toString() {
        return "ExtensionDeclaration{name='" + name + "', implementation='" + implementation
                + "', version='" + version + "', priority=" + priority
                + ", requires=" + requires + ", minFrameworkVersion='" + minFrameworkVersion + "'}";
    }
}
