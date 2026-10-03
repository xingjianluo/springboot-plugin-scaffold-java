package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

/**
 * 一次增量刷新中，单个扩展实际受到的处理动作。
 */
public enum ExtensionAction {

    /** 旧集合中不存在，本次新建并激活。 */
    ADDED,

    /** 新集合中不存在，本次停用并移除。 */
    REMOVED,

    /** 实现或其依赖链发生变化，本次销毁旧实例、创建并激活新实例。 */
    REBUILT,

    /** 未发生实质变化，沿用当前正在运行的同一个实例（不停用、不重新激活）。 */
    REUSED
}
