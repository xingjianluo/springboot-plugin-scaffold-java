package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

/**
 * 扩展运行时管理器：持有当前生效快照，支持原子刷新与稳定查询。
 */
public interface ExtensionManager {

    /**
     * 增量、原子地把生效集合切换为新配置：只有真正变化（含依赖连带）的扩展才重建，
     * 未变化的扩展沿用同一份运行实例；相同配置幂等跳过；任何一步失败整体回退，
     * 旧集合保持可用。返回本次逐扩展的处理报告。
     */
    RefreshReport refresh(ExtensionConfiguration configuration);

    /**
     * 回退到上一次成功生效的配置：复用当时快照的同一批实例（而非照配置重建），
     * 切换同样原子、失败整体回退。没有可回退的快照时抛出
     * {@link IllegalStateException}。
     */
    RefreshReport restore();

    /** 当前生效快照（永不为 null；未加载时为空快照）。 */
    EnabledExtensions current();
}
