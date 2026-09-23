package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

/**
 * 扩展运行时管理器：持有当前生效快照，支持原子刷新与稳定查询。
 */
public interface ExtensionManager {

    /** 原子替换生效集合；相同配置幂等跳过；失败时旧集合保持不变。 */
    void refresh(ExtensionConfiguration configuration);

    /** 当前生效快照（永不为 null；未加载时为空快照）。 */
    EnabledExtensions current();
}
