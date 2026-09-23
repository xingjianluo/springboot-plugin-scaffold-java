package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

/**
 * 扩展被拒绝加载的可区分原因。
 */
public enum LoadFailureReason {

    /** 同一扩展名称被重复声明 */
    DUPLICATE_DECLARATION,

    /** 声明的实现类不存在、不可见或未实现 {@link PluginExtension} */
    UNDECLARED_IMPLEMENTATION,

    /** 实现类存在但无法实例化（抽象类、无公开无参构造、构造抛出异常等） */
    UNINSTANTIABLE_IMPLEMENTATION,

    /** 声明依赖了一个未启用的扩展 */
    MISSING_DEPENDENCY,

    /** 依赖存在但版本低于要求的最低版本 */
    DEPENDENCY_VERSION_NOT_SATISFIED,

    /** 扩展之间存在循环依赖 */
    CIRCULAR_DEPENDENCY,

    /** 扩展要求的最低框架版本高于当前框架版本 */
    FRAMEWORK_VERSION_NOT_SATISFIED
}
