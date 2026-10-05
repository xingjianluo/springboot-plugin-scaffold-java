package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于 {@link AtomicReference} 的增量热更新管理器：
 * <ul>
 *   <li>查询永远读到完整旧快照或完整新快照，切换期间不会观察到半更新或新旧混用状态；</li>
 *   <li>刷新经同一把锁串行化，并按配置指纹幂等跳过相同提交（不产生任何重建）；</li>
 *   <li>只有真正变化（实现变更或其依赖链变化）的扩展才重建，其余沿用同一运行实例：
 *       不重新构造、不停用、不重新激活，创建/初始化从头到尾只发生一次；
 *       生命周期回调与差异规模成正比；</li>
 *   <li>新实例先离线校验、再依次激活；任一步失败则反向停用本次新激活者、
 *       回收本次新建但从未启用的实例（deactivate 释放其资源），并整体回退，
 *       旧集合实例始终保持可用，未变化扩展也不会因此多创建一份；</li>
 *   <li>记录上一次成功生效快照，{@link #rollback()} 一步恢复并复用当时的实例。</li>
 * </ul>
 */
public class DefaultExtensionManager implements ExtensionManager {

    private static final Logger log = LoggerFactory.getLogger(DefaultExtensionManager.class);

    private final ExtensionPlanBuilder planBuilder;
    private final ExtensionRefreshPlanner refreshPlanner;
    private final AtomicReference<EnabledExtensions> active;
    /** 上一次成功生效的快照；与 active 初始同为空快照时视为“无可回退目标”。 */
    private volatile EnabledExtensions previous;
    private final Object refreshLock = new Object();

    public DefaultExtensionManager(ExtensionPlanBuilder planBuilder) {
        this(planBuilder, new ExtensionRefreshPlanner());
    }

    public DefaultExtensionManager(ExtensionPlanBuilder planBuilder,
                                   ExtensionRefreshPlanner refreshPlanner) {
        this.planBuilder = planBuilder;
        this.refreshPlanner = refreshPlanner;
        ExtensionConfiguration initial = new ExtensionConfiguration("0.0.0", List.of());
        EnabledExtensions empty = new EnabledExtensions(initial, List.of());
        this.active = new AtomicReference<>(empty);
        this.previous = null;
    }

    @Override
    public RefreshReport refresh(ExtensionConfiguration configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("configuration must not be null");
        }
        synchronized (refreshLock) {
            EnabledExtensions currentSnapshot = active.get();
            String fingerprint = configuration.fingerprint();
            log.info("incremental refresh submitted: fingerprint={}, declaredCount={}",
                    fingerprint, configuration.declarations().size());

            if (fingerprint.equals(currentSnapshot.configuration().fingerprint())) {
                log.info("refresh skipped as idempotent: same fingerprint={}, no instance touched",
                        fingerprint);
                return new RefreshReport(false, false,
                        currentSnapshot.configuration().fingerprint(), fingerprint,
                        unchangedChanges(currentSnapshot));
            }

            // 离线增量构建：与运行实例同类的声明直接沿用该实例，只有新增/实现变更/
            // 依赖连带的扩展才会新建；任何校验失败都在此抛出，旧状态原样保留，
            // 本次新建但从未启用的实例由构建器负责回收
            EnabledExtensions built = planBuilder.build(configuration, currentSnapshot);

            // 差异判定：决定新增/移除/重建/沿用与依赖连带
            IncrementalPlan plan = refreshPlanner.plan(currentSnapshot, built);
            log.info("incremental diff computed: added={}, rebuilt={}, removed={}, reused={}",
                    count(plan, ExtensionAction.ADDED), count(plan, ExtensionAction.REBUILT),
                    count(plan, ExtensionAction.REMOVED), count(plan, ExtensionAction.REUSED));
            plan.changes().forEach(change -> log.info("diff: {}", change.describe()));

            applyPlan(plan, currentSnapshot);

            active.set(plan.targetSnapshot());
            previous = currentSnapshot;
            log.info("active snapshot switched atomically: newIds={}, previousIds={}",
                    plan.targetSnapshot().ids(), currentSnapshot.ids());

            deactivateRetired(plan.toDeactivate());
            return new RefreshReport(true, false,
                    currentSnapshot.configuration().fingerprint(), fingerprint, plan.changes());
        }
    }

    @Override
    public RefreshReport rollback() {
        synchronized (refreshLock) {
            EnabledExtensions currentSnapshot = active.get();
            EnabledExtensions target = previous;
            if (target == null) {
                throw new IllegalStateException(
                        "no previous successfully applied snapshot to roll back to");
            }
            log.info("rollback submitted: currentFingerprint={}, targetFingerprint={}",
                    currentSnapshot.configuration().fingerprint(),
                    target.configuration().fingerprint());

            // 直接复用上一次成功快照里记录在案的实例，不做任何重建
            IncrementalPlan plan = refreshPlanner.restorePlan(currentSnapshot, target);
            log.info("rollback diff computed: restore={}, reuse={}, remove={}",
                    count(plan, ExtensionAction.REBUILT), count(plan, ExtensionAction.REUSED),
                    count(plan, ExtensionAction.REMOVED));
            plan.changes().forEach(change -> log.info("rollback diff: {}", change.describe()));

            applyPlan(plan, currentSnapshot);

            active.set(plan.targetSnapshot());
            previous = currentSnapshot;
            log.info("rollback switched atomically: restoredIds={}, previousIds={}",
                    plan.targetSnapshot().ids(), currentSnapshot.ids());

            deactivateRetired(plan.toDeactivate());
            return new RefreshReport(true, true,
                    currentSnapshot.configuration().fingerprint(),
                    target.configuration().fingerprint(), plan.changes());
        }
    }

    @Override
    public EnabledExtensions current() {
        return active.get();
    }

    /** 依次激活计划中的新实例；失败则反向停用本次已激活者并整体拒绝，旧状态不动。 */
    private void applyPlan(IncrementalPlan plan, EnabledExtensions currentSnapshot) {
        for (int i = 0; i < plan.toActivate().size(); i++) {
            Extension extension = plan.toActivate().get(i);
            try {
                extension.activate();
                log.info("extension activated: id={}, order={}", extension.id(), i);
            } catch (RuntimeException ex) {
                for (int j = i - 1; j >= 0; j--) {
                    Extension activated = plan.toActivate().get(j);
                    try {
                        activated.deactivate();
                    } catch (RuntimeException deactivateEx) {
                        log.warn("rollback deactivate() failed: id={}", activated.id(),
                                deactivateEx);
                    }
                }
                // 激活失败者和还没轮到激活的新实例同样要回收，
                // 不能留下“建出来却没启用”的孤儿实例占着资源
                for (int j = plan.toActivate().size() - 1; j >= i; j--) {
                    Extension neverActivated = plan.toActivate().get(j);
                    try {
                        neverActivated.deactivate();
                        log.info("recycled never-activated instance after rejection: id={}",
                                neverActivated.id());
                    } catch (RuntimeException deactivateEx) {
                        log.warn("recycle deactivate() failed: id={}", neverActivated.id(),
                                deactivateEx);
                    }
                }
                ExtensionFailure failure = new ExtensionFailure(
                        ExtensionFailureReason.ACTIVATION_FAILED,
                        extension.id(), null, "activate() threw: " + ex);
                log.error("refresh rejected, rollback completed, previous snapshot still usable: "
                        + "previousIds={}, {}", currentSnapshot.ids(), failure.describe(), ex);
                throw new ExtensionLoadException(List.of(failure));
            }
        }
    }

    /** 原子切换成功后，按旧激活顺序的逆序停用被移除/替换的旧实例，尽力而为。 */
    private void deactivateRetired(List<Extension> retired) {
        for (Extension old : retired) {
            try {
                old.deactivate();
                log.info("old extension deactivated: id={}", old.id());
            } catch (RuntimeException ex) {
                log.warn("old extension deactivate() failed, active set unaffected: id={}",
                        old.id(), ex);
            }
        }
    }

    private static long count(IncrementalPlan plan, ExtensionAction action) {
        return plan.changes().stream().filter(change -> change.action() == action).count();
    }

    private static List<ExtensionChange> unchangedChanges(EnabledExtensions snapshot) {
        List<ExtensionChange> changes = new ArrayList<>();
        for (Extension extension : snapshot.extensions()) {
            changes.add(new ExtensionChange(extension.id(), ExtensionAction.REUSED,
                    ExtensionChangeReason.UNCHANGED,
                    "identical configuration fingerprint; idempotent submission, nothing rebuilt"));
        }
        return changes;
    }
}
