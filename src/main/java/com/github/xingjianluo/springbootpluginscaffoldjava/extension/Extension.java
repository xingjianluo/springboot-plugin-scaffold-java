package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

import java.util.Set;

/**
 * 可插拔扩展的统一接口。所有扩展实现必须提供无参构造器，
 * 以便加载器可以通过反射实例化。
 */
public interface Extension {

    /** 扩展唯一标识，同一启用集合内不可重复。 */
    String id();

    /** 扩展自身版本号。 */
    default String version() {
        return "1.0.0";
    }

    /** 声明顺序优先级，数值越小越靠前；默认 100。 */
    default int priority() {
        return 100;
    }

    /** 所依赖的其他扩展 id 集合，被依赖方保证先于本扩展激活。 */
    default Set<String> requiredExtensions() {
        return Set.of();
    }

    /** 运行所需的最低框架版本（含），低于该版本则拒绝加载。 */
    default String requiredFrameworkVersion() {
        return "0.0.0";
    }

    /** 扩展进入生效集合时回调。抛异常将导致本次刷新整体回退。 */
    default void activate() {
    }

    /**
     * 扩展退出生效集合时回调。
     * 注意：为释放构造阶段已占用的资源，本方法也可能在实例<b>从未成功激活</b>时
     * 被调用（例如本次提交被整体拒绝、实例建出来却未启用），实现需保证幂等容错。
     */
    default void deactivate() {
    }
}
