package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

/**
 * 扩展加载/刷新失败的可区分原因。
 */
public enum ExtensionFailureReason {

    /** 配置中声明的实现不在可用扩展注册中心内。 */
    UNDECLARED_IMPLEMENTATION,

    /** 实现已注册，但无法通过无参构造器实例化。 */
    INSTANTIATION_FAILED,

    /** 同一实现（类名或扩展 id）在配置中被重复声明。 */
    DUPLICATE_DECLARATION,

    /** 扩展声明依赖的另一个扩展未在本次启用集合中。 */
    MISSING_DEPENDENCY,

    /** 扩展要求的最低框架版本高于当前框架版本。 */
    INCOMPATIBLE_VERSION,

    /** 扩展之间的依赖关系存在环。 */
    CIRCULAR_DEPENDENCY,

    /** 扩展激活回调抛出异常。 */
    ACTIVATION_FAILED
}
