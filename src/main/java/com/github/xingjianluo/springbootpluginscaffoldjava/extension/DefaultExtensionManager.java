package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于 {@link AtomicReference} 的增量热更新管理器：
 * <ul>
 *   <li>查询永远读到完整旧快照或完整新快照，切换期间不会观察到半更新或新旧混用状态；</li>
 *   <li>刷新经同一把锁串行化，并按配置指纹幂等跳过相同提交（不产生任何重建）；</li>
 *   <li>只有真正变化（实现变更或其依赖链变化）的扩展才重建，其余沿用同一运行实例：
 *       既不停用也不重新激活，<b>构造器也不会被再次调用</b>——未变化扩展的创建/初始化
 *       在其整个生命周期内只发生一次；生命周期回调与差异规模成正比；</li>
 *   <li>新实例先离线校验、再依次激活；任一步失败则反向停用本次新激活者、回收本次新建
 *       却从未启用的实例并整体回退，旧集合实例始终保持可用，被拒绝的提交不留下
 *       任何占用资源的孤儿例；</li>
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

            // 离线构建 + 差异判定的不动点循环：
            // 复用池保证未变化扩展自始至终只有运行中的那一份实例（构造器零调用）；
            // 被判 REBUILT 但池中只有旧实例的（依赖连带），移出复用池后重新构建，
            // 直到计划要求的每个重建都持有对应的新实例。循环每轮至少新增一个强制重建 id，
            // 因而必然终止。
            Map<String, Extension> reusePool = new HashMap<>();
            for (Extension running : currentSnapshot.extensions()) {
                reusePool.put(running.getClass().getName(), running);
            }
            List<Extension> createdTotal = new ArrayList<>();
            IncrementalPlan plan;
            while (true) {
                BuiltSnapshot built;
                try {
                    built = planBuilder.build(configuration, reusePool);
                } catch (ExtensionLoadException ex) {
                    // 本次 build 新建的实例已由 builder 回收；这里回收此前各轮新建
                    // 却从未启用的实例，被拒绝的提交不留下任何占用资源的孤儿实例
                    deactivateQuietly(createdTotal, "rejected submission cleanup");
                    log.error("refresh rejected during offline build, previous snapshot intact: "
                            + "previousIds={}, failures={}", currentSnapshot.ids(),
                            ex.failures().stream().map(ExtensionFailure::describe).toList());
                    throw ex;
                }
                createdTotal.addAll(built.createdInstances());
                // 本轮新建实例并入复用池：若还需下一轮构建，新增/替换的扩展不会重复构造
                for (Extension created : built.createdInstances()) {
                    reusePool.put(created.getClass().getName(), created);
                }

                plan = refreshPlanner.plan(currentSnapshot, built.snapshot());
                Set<String> needsFreshInstance = new HashSet<>();
                for (ExtensionChange change : plan.changes()) {
                    if (change.action() == ExtensionAction.REBUILT
                            && built.snapshot().byId(change.id()) == currentSnapshot.byId(change.id())) {
                        // 计划要求重建，但手里还是沿用池中的旧实例：强制真正重建
                        needsFreshInstance.add(change.id());
                    }
                }
                if (needsFreshInstance.isEmpty()) {
                    break;
                }
                log.info("dependency-triggered rebuild requires fresh instances: ids={}",
                        needsFreshInstance);
                for (String id : needsFreshInstance) {
                    Extension running = currentSnapshot.byId(id);
                    if (running != null) {
                        reusePool.remove(running.getClass().getName());
                    }
                }
            }
            log.info("incremental diff computed: added={}, rebuilt={}, removed={}, reused={}",
                    count(plan, ExtensionAction.ADDED), count(plan, ExtensionAction.REBUILT),
                    count(plan, ExtensionAction.REMOVED), count(plan, ExtensionAction.REUSED));
            plan.changes().forEach(change -> log.info("diff: {}", change.describe()));

            applyPlan(plan, currentSnapshot, createdTotal);

            active.set(plan.targetSnapshot());
            previous = currentSnapshot;
            log.info("active snapshot switched atomically: newIds={}, previousIds={}",
                    plan.targetSnapshot().ids(), currentSnapshot.ids());

            deactivateRetired(plan.toDeactivate());
            // 安全网：新建却最终未进入目标快照的实例（正常流程不应出现）必须回收
            List<Extension> orphaned = new ArrayList<>(createdTotal);
            orphaned.removeAll(plan.targetSnapshot().extensions());
            deactivateQuietly(orphaned, "created but not part of the applied snapshot");
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

            applyPlan(plan, currentSnapshot, List.of());

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

    /**
     * 依次激活计划中的新实例；失败则反向停用本次已激活者、回收本次新建却从未激活的
     * 实例，并整体拒绝，旧状态不动。
     *
     * @param createdThisRefresh 本次刷新新建的全部实例（用于失败时回收未激活者）
     */
    private void applyPlan(IncrementalPlan plan, EnabledExtensions currentSnapshot,
                           List<Extension> createdThisRefresh) {
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
                // 本次新建但还没来得及激活的实例也要回收，不能留下占用资源的孤儿
                List<Extension> neverActivated = new ArrayList<>(createdThisRefresh);
                for (int j = 0; j < i; j++) {
                    neverActivated.remove(plan.toActivate().get(j));
                }
                deactivateQuietly(neverActivated, "never activated before rejection");
                ExtensionFailure failure = new ExtensionFailure(
                        ExtensionFailureReason.ACTIVATION_FAILED,
                        extension.id(), null, "activate() threw: " + ex);
                log.error("refresh rejected, rollback completed, previous snapshot still usable: "
                        + "previousIds={}, {}", currentSnapshot.ids(), failure.describe(), ex);
                throw new ExtensionLoadException(List.of(failure));
            }
        }
    }

    /** 尽力停用一批实例（用于被拒绝提交的清理），异常只记日志不影响旧状态。 */
    private void deactivateQuietly(List<Extension> instances, String why) {
        for (Extension instance : instances) {
            try {
                instance.deactivate();
                log.info("discarded instance deactivated ({}): id={}", why, instance.id());
            } catch (RuntimeException ex) {
                log.warn("deactivate() of a discarded instance failed ({}): id={}",
                        why, instance.id(), ex);
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
