package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于 {@link AtomicReference} 的双缓冲管理器：
 * <ul>
 *   <li>查询永远读到完整旧快照或完整新快照，刷新期间不会观察到半加载状态；</li>
 *   <li>刷新通过同一把锁串行化，并按配置指纹幂等跳过相同提交；</li>
 *   <li>新集合任一扩展激活失败则全部回滚，旧集合保持不变。</li>
 * </ul>
 */
public class DefaultExtensionManager implements ExtensionManager {

    private static final Logger log = LoggerFactory.getLogger(DefaultExtensionManager.class);

    private final ExtensionPlanBuilder planBuilder;
    private final AtomicReference<EnabledExtensions> active;
    private final Object refreshLock = new Object();

    public DefaultExtensionManager(ExtensionPlanBuilder planBuilder) {
        this.planBuilder = planBuilder;
        ExtensionConfiguration initial = new ExtensionConfiguration("0.0.0", List.of());
        this.active = new AtomicReference<>(new EnabledExtensions(initial, List.of()));
    }

    @Override
    public void refresh(ExtensionConfiguration configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("configuration must not be null");
        }
        synchronized (refreshLock) {
            EnabledExtensions currentSnapshot = active.get();
            String fingerprint = configuration.fingerprint();
            log.info("refresh submitted: fingerprint={}, declaredCount={}",
                    fingerprint, configuration.declarations().size());

            if (fingerprint.equals(currentSnapshot.configuration().fingerprint())) {
                log.info("refresh skipped as idempotent: same fingerprint={}", fingerprint);
                return;
            }

            // 离线构建：任何校验/实例化失败都在此抛出，旧状态原样保留
            EnabledExtensions plan = planBuilder.build(configuration);
            log.info("plan built successfully: orderedIds={}", plan.ids());

            // 先激活新扩展（全部为全新实例）；失败则反向停用已激活者并整体回退
            for (int i = 0; i < plan.extensions().size(); i++) {
                Extension extension = plan.extensions().get(i);
                try {
                    extension.activate();
                    log.info("extension activated: id={}, order={}", extension.id(), i);
                } catch (RuntimeException ex) {
                    rollbackActivation(plan, i);
                    ExtensionFailure failure = new ExtensionFailure(
                            ExtensionFailureReason.ACTIVATION_FAILED,
                            extension.id(), null,
                            "activate() threw: " + ex);
                    log.error("refresh rejected, rollback completed: {}", failure.describe(), ex);
                    throw new ExtensionLoadException(List.of(failure));
                }
            }

            // 原子切换对外可见状态
            active.set(plan);
            log.info("active snapshot switched atomically: newIds={}, previousIds={}",
                    plan.ids(), currentSnapshot.ids());

            // 切换后停用旧扩展实例，尽力而为，不影响已生效集合
            for (int i = currentSnapshot.extensions().size() - 1; i >= 0; i--) {
                Extension old = currentSnapshot.extensions().get(i);
                try {
                    old.deactivate();
                    log.info("old extension deactivated: id={}", old.id());
                } catch (RuntimeException ex) {
                    log.warn("old extension deactivate() failed, active set unaffected: id={}",
                            old.id(), ex);
                }
            }
        }
    }

    @Override
    public EnabledExtensions current() {
        return active.get();
    }

    private void rollbackActivation(EnabledExtensions plan, int activatedCount) {
        for (int i = activatedCount - 1; i >= 0; i--) {
            Extension extension = plan.extensions().get(i);
            try {
                extension.deactivate();
            } catch (RuntimeException ex) {
                log.warn("rollback deactivate() failed: id={}", extension.id(), ex);
            }
        }
    }
}
