package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

/**
 * 一次（增量）刷新或回退中，单个扩展被判定的处理动作。
 */
public enum ExtensionAction {

    /** 新配置中新增：此前不在生效集合，需要新建实例并激活。 */
    ADDED,

    /** 已从配置中移除：切换后停用其旧实例。 */
    REMOVED,

    /**
     * 配置中仍保留该扩展，但必须重建：实现类被换掉，
     * 或它依赖（传递依赖）的对象被换掉/移除而引发的连带重建。
     */
    RECREATED,

    /** 沿用当前正在运行的同一份实例：不重建、不停启、顺序与引用均保持不变。 */
    RETAINED,

    /** 回退到上一个成功快照：该实例属于当时快照且此前已停用，被同一对象重新激活带回。 */
    RESTORED
}
