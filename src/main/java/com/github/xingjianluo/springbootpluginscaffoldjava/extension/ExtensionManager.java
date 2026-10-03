package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

/**
 * 扩展运行时管理器：持有当前生效快照，支持增量原子刷新、幂等提交、失败整体回退，
 * 以及回到上一次成功生效配置。
 */
public interface ExtensionManager {

    /**
     * 增量、原子地替换生效集合：
     * 只有实现或依赖链真正发生变化的扩展才会重建，未变化者沿用同一运行实例；
     * 相同配置幂等跳过；任何一步失败则整体回退，旧集合保持可用。
     *
     * @return 本次刷新的逐扩展处理结论
     */
    RefreshReport refresh(ExtensionConfiguration configuration);

    /**
     * 一步回到上一次成功生效的配置，且复用当时的运行实例，而不是照配置重新构建。
     * 没有可回到的上一快照时抛出 {@link IllegalStateException}。
     *
     * @return 回滚操作的逐扩展处理结论
     */
    RefreshReport rollback();

    /** 当前生效快照（永不为 null；未加载时为空快照）。 */
    EnabledExtensions current();
}
