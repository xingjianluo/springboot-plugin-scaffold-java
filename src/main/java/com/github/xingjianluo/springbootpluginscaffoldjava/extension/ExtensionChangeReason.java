package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

/**
 * 单个扩展被判定为某一动作的原因，用于让刷新结果可解释。
 */
public enum ExtensionChangeReason {

    /** 旧生效集合中没有该 id：新启用。 */
    NEW_ENABLED,

    /** 新配置不再声明该 id：停用移除。 */
    REMOVED_FROM_CONFIG,

    /** 同一 id 换了实现类（或同一 id 的实现不再是原来的类）。 */
    IMPLEMENTATION_CHANGED,

    /** 自身未变，但它依赖的某个扩展被新建/重建，为避免新旧版本混用而连带重建。 */
    DEPENDENCY_REBUILT,

    /** 仅优先级覆盖发生变化：实例沿用，只改变生效顺序。 */
    ORDER_ONLY_CHANGED,

    /** 实现与依赖均无变化：实例完全沿用。 */
    UNCHANGED,

    /** 回到上一次成功配置：恢复当时记录在案的实例（重新激活），而非照配置重新构建。 */
    RESTORED_FROM_PREVIOUS
}
