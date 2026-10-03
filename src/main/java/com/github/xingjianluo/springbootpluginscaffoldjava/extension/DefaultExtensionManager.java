package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 增量热更新管理器：
 * <ul>
 *   <li>刷新时只有真正变化（实现类变化或依赖被换掉的连带闭包）的扩展才新建、激活，
 *       未变化的扩展沿用正在运行的同一份实例，不停启、不换引用；</li>
 *   <li>新建实例全部激活成功后才经 {@link AtomicReference} 原子切换对外状态，
 *       查询永远读到完整旧快照或完整新快照；任何一步失败则反向停用刚激活的新实例，
 *       旧集合原样保留、继续可用；</li>
 *   <li>同一份配置重复提交按指纹幂等跳过，不产生任何重建；</li>
 *   <li>刷新在同一把锁内串行化，并发提交的最终结果必为某个合法完整快照；
 *       工作量只与差异规模成正比（仅新建实例需要 activate）；</li>
 *   <li>{@link #restore()} 回退到上一次成功生效的配置，复用当时快照的同一批实例。</li>
 * </ul>
 */
public class DefaultExtensionManager implements ExtensionManager {

    private static final Logger log = LoggerFactory.getLogger(DefaultExtensionManager.class);

    private final ExtensionPlanBuilder planBuilder;
    private final AtomicReference<EnabledExtensions> active;
    private final Object refreshLock = new Object();

    /** 上一次成功生效的快照；回退时直接复用其实例，而非照配置重建。 */
    private EnabledExtensions previousSuccess;

    public DefaultExtensionManager(ExtensionPlanBuilder planBuilder) {
        this.planBuilder = planBuilder;
        ExtensionConfiguration initial = new ExtensionConfiguration("0.0.0", List.of());
        this.active = new AtomicReference<>(new EnabledExtensions(initial, List.of()));
        this.previousSuccess = null;
    }

    @Override
    public RefreshReport refresh(ExtensionConfiguration configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("configuration must not be null");
        }
        synchronized (refreshLock) {
            EnabledExtensions currentSnapshot = active.get();
            String fingerprint = configuration.fingerprint();
            log.info("incremental refresh submitted: fingerprint={}, declaredCount={}, currentIds={}",
                    fingerprint, configuration.declarations().size(), currentSnapshot.ids());

            if (fingerprint.equals(currentSnapshot.configuration().fingerprint())) {
                List<ExtensionDecision> decisions = new ArrayList<>();
                for (Extension extension : currentSnapshot.extensions()) {
                    decisions.add(new ExtensionDecision(extension.id(), ExtensionAction.RETAINED,
                            "identical configuration fingerprint, idempotent submission, "
                                    + "running instance reused without any rebuild"));
                }
                log.info("refresh skipped as idempotent: same fingerprint={}, decisions={}",
                        fingerprint, decisions.stream().map(d -> d.id() + ":" + d.action()).toList());
                return new RefreshReport(RefreshReport.MODE_REFRESH, fingerprint, true,
                        decisions, currentSnapshot.ids());
            }

            // 离线构建增量计划：完成全部差异判定与校验，不触碰任何运行实例
            ExtensionPlanBuilder.IncrementalPlan plan =
                    planBuilder.buildIncremental(configuration, currentSnapshot);
            Set<String> reusedIds = new HashSet<>(plan.reusedIds());
            log.info("incremental plan built: orderedIds={}, reusedIds={}, freshIds={}",
                    plan.snapshot().ids(), reusedIds,
                    plan.snapshot().ids().stream().filter(id -> !reusedIds.contains(id)).toList());
            plan.decisions().forEach(d -> log.info("decision: id={}, action={}, reason={}",
                    d.id(), d.action(), d.reason()));

            // 仅激活“新建”实例（新增 + 连带重建），沿用实例保持运行、不重复回调。
            // 按拓扑序遍历可保证被依赖者先激活。
            List<Extension> activatedFresh = new ArrayList<>();
            for (Extension extension : plan.snapshot().extensions()) {
                if (reusedIds.contains(extension.id())) {
                    continue;
                }
                try {
                    extension.activate();
                    activatedFresh.add(extension);
                    log.info("fresh extension activated: id={}", extension.id());
                } catch (RuntimeException ex) {
                    rollbackFreshActivation(activatedFresh);
                    ExtensionFailure failure = new ExtensionFailure(
                            ExtensionFailureReason.ACTIVATION_FAILED,
                            extension.id(), null,
                            "activate() threw during incremental refresh: " + ex);
                    log.error("incremental refresh rejected, rollback completed: {}",
                            failure.describe(), ex);
                    throw new ExtensionLoadException(List.of(failure));
                }
            }

            // 原子切换：此后查询只能看到完整新快照
            active.set(plan.snapshot());
            previousSuccess = currentSnapshot;
            log.info("active snapshot switched atomically: newIds={}, previousIds={}",
                    plan.snapshot().ids(), currentSnapshot.ids());

            // 切换后仅停用“被替换/移除”的旧实例；沿用实例的旧引用仍在新快照中，绝不停用。
            Map<String, Extension> freshById = new LinkedHashMap<>();
            plan.snapshot().extensions().forEach(e -> freshById.put(e.id(), e));
            for (int i = currentSnapshot.extensions().size() - 1; i >= 0; i--) {
                Extension old = currentSnapshot.extensions().get(i);
                Extension inNew = freshById.get(old.id());
                if (inNew == old) {
                    continue;
                }
                try {
                    old.deactivate();
                    log.info("superseded old extension deactivated: id={}", old.id());
                } catch (RuntimeException ex) {
                    log.warn("old extension deactivate() failed, active set unaffected: id={}",
                            old.id(), ex);
                }
            }

            return new RefreshReport(RefreshReport.MODE_REFRESH, fingerprint, false,
                    plan.decisions(), plan.snapshot().ids());
        }
    }

    @Override
    public RefreshReport restore() {
        synchronized (refreshLock) {
            EnabledExtensions currentSnapshot = active.get();
            if (previousSuccess == null) {
                throw new IllegalStateException(
                        "no previous successful snapshot is available to restore");
            }
            EnabledExtensions target = previousSuccess;
            String fingerprint = target.configuration().fingerprint();
            log.info("restore submitted: targetFingerprint={}, targetIds={}, currentIds={}",
                    fingerprint, target.ids(), currentSnapshot.ids());

            // 目标实例即上一次成功快照的同一批对象：两快照共有的实例本就在运行，
            // 仅需重新激活“目标独有、此前已停用”的实例（按拓扑序）。
            Map<String, Extension> currentById = new LinkedHashMap<>();
            currentSnapshot.extensions().forEach(e -> currentById.put(e.id(), e));

            List<ExtensionDecision> decisions = new ArrayList<>();
            List<Extension> toActivate = new ArrayList<>();
            for (Extension extension : target.extensions()) {
                Extension current = currentById.get(extension.id());
                if (current == extension) {
                    decisions.add(new ExtensionDecision(extension.id(), ExtensionAction.RETAINED,
                            "instance shared with the current snapshot, kept running"));
                } else {
                    toActivate.add(extension);
                    decisions.add(new ExtensionDecision(extension.id(), ExtensionAction.RESTORED,
                            "reusing the exact instance from the previous successful snapshot, "
                                    + "re-activated without rebuilding"));
                }
            }
            Set<String> targetIds = new HashSet<>(target.ids());
            for (int i = currentSnapshot.extensions().size() - 1; i >= 0; i--) {
                Extension old = currentSnapshot.extensions().get(i);
                if (!targetIds.contains(old.id())) {
                    decisions.add(new ExtensionDecision(old.id(), ExtensionAction.REMOVED,
                            "not part of the restored snapshot, deactivated after switch"));
                }
            }

            List<Extension> activated = new ArrayList<>();
            for (Extension extension : toActivate) {
                try {
                    extension.activate();
                    activated.add(extension);
                    log.info("restored extension re-activated: id={}", extension.id());
                } catch (RuntimeException ex) {
                    rollbackFreshActivation(activated);
                    ExtensionFailure failure = new ExtensionFailure(
                            ExtensionFailureReason.ACTIVATION_FAILED,
                            extension.id(), null,
                            "activate() threw during restore: " + ex);
                    log.error("restore rejected, rollback completed, current snapshot intact: {}",
                            failure.describe(), ex);
                    throw new ExtensionLoadException(List.of(failure));
                }
            }

            // 原子切回：上一次成功快照成为当前，当前快照成为新的“上一次”，
            // 因而连续回退等价于在最近两份成功配置之间来回切换。
            active.set(target);
            previousSuccess = currentSnapshot;
            log.info("active snapshot restored atomically: restoredIds={}, supersededIds={}",
                    target.ids(), currentSnapshot.ids());

            // 停用“当前独有、目标不含”的实例（目标独有的刚刚已重新激活）
            for (int i = currentSnapshot.extensions().size() - 1; i >= 0; i--) {
                Extension old = currentSnapshot.extensions().get(i);
                if (!targetIds.contains(old.id())) {
                    try {
                        old.deactivate();
                        log.info("superseded extension deactivated after restore: id={}", old.id());
                    } catch (RuntimeException ex) {
                        log.warn("extension deactivate() failed after restore, active set "
                                + "unaffected: id={}", old.id(), ex);
                    }
                }
            }

            return new RefreshReport(RefreshReport.MODE_RESTORE, fingerprint, false,
                    decisions, target.ids());
        }
    }

    @Override
    public EnabledExtensions current() {
        return active.get();
    }

    private void rollbackFreshActivation(List<Extension> activatedFresh) {
        for (int i = activatedFresh.size() - 1; i >= 0; i--) {
            Extension extension = activatedFresh.get(i);
            try {
                extension.deactivate();
            } catch (RuntimeException ex) {
                log.warn("rollback deactivate() failed: id={}", extension.id(), ex);
            }
        }
    }
}
