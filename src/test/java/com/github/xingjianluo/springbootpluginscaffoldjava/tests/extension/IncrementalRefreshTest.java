package com.github.xingjianluo.springbootpluginscaffoldjava.tests.extension;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.DefaultExtensionManager;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.DefaultExtensionRegistry;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionAction;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionChange;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionChangeReason;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionConfiguration;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionDeclaration;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionLoadException;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionManager;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionPlanBuilder;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionRegistry;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.RefreshReport;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.ActivateFailingExtension;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.AlphaAlternativeExtension;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.AlphaExtension;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.BetaActivateFailingExtension;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.BetaExtension;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.DeltaExtension;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.InstanceTrackingExtension;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.LifecycleExtension;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.TrackerAlphaDependentExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 增量热更新测试：实例复用、依赖连带重建、失败整体回退且旧实例可用、
 * 重复提交幂等、并发读写只见完整快照、回到上一次成功配置。
 * 每个用例都打印输入配置、差异判定与处理结果。
 */
class IncrementalRefreshTest {

    private static final Logger log = LoggerFactory.getLogger(IncrementalRefreshTest.class);

    private ExtensionManager manager;

    private static ExtensionConfiguration config(String fw, String... classNames) {
        return ExtensionConfiguration.of(fw, Arrays.stream(classNames)
                .map(ExtensionDeclaration::of).toList());
    }

    private static ExtensionConfiguration configWithPriority(String fw, String className, int priority) {
        return ExtensionConfiguration.of(fw,
                List.of(new ExtensionDeclaration(className, priority)));
    }

    private static Map<String, ExtensionChange> byId(RefreshReport report) {
        return report.changes().stream()
                .collect(Collectors.toMap(ExtensionChange::id, Function.identity()));
    }

    private static void assertAction(RefreshReport report, String id, ExtensionAction action,
                                    ExtensionChangeReason reason) {
        ExtensionChange change = byId(report).get(id);
        assertEquals(action, change.action(), () -> id + " action, detail=" + change.describe());
        assertEquals(reason, change.reason(), () -> id + " reason, detail=" + change.describe());
    }

    @BeforeEach
    void setUp() {
        ExtensionRegistry registry = new DefaultExtensionRegistry();
        registry.register(AlphaExtension.class);
        registry.register(AlphaAlternativeExtension.class);
        registry.register(BetaExtension.class);
        registry.register(BetaActivateFailingExtension.class);
        registry.register(LifecycleExtension.class);
        registry.register(InstanceTrackingExtension.class);
        registry.register(TrackerAlphaDependentExtension.class);
        registry.register(ActivateFailingExtension.class);
        registry.register(DeltaExtension.class);
        LifecycleExtension.reset();
        InstanceTrackingExtension.reset();
        manager = new DefaultExtensionManager(new ExtensionPlanBuilder(registry));
    }

    @Test
    void unrelatedExtensionAddedKeepsRunningInstancesUntouched() {
        ExtensionConfiguration first = config("1.0.0",
                BetaExtension.class.getName(), AlphaExtension.class.getName());
        manager.refresh(first);
        Object alphaBefore = manager.current().byId("alpha");
        Object betaBefore = manager.current().byId("beta");
        log.info("input[1]={}, instances: alpha={}, beta={}", first.fingerprint(),
                System.identityHashCode(alphaBefore), System.identityHashCode(betaBefore));

        ExtensionConfiguration second = config("1.0.0",
                BetaExtension.class.getName(), AlphaExtension.class.getName(),
                LifecycleExtension.class.getName());
        RefreshReport report = manager.refresh(second);
        log.info("input[2] adds lifecycle only; diff result: {}",
                report.changes().stream().map(ExtensionChange::describe).toList());

        assertAction(report, "lifecycle", ExtensionAction.ADDED, ExtensionChangeReason.NEW_ENABLED);
        assertAction(report, "alpha", ExtensionAction.REUSED, ExtensionChangeReason.UNCHANGED);
        assertAction(report, "beta", ExtensionAction.REUSED, ExtensionChangeReason.UNCHANGED);
        assertTrue(report.applied());

        // 同一份运行实例沿用，没有停了再启
        assertSame(alphaBefore, manager.current().byId("alpha"), "alpha instance must be reused");
        assertSame(betaBefore, manager.current().byId("beta"), "beta instance must be reused");
        List<String> lifecycleEvents = LifecycleExtension.EVENTS;
        assertEquals(1, lifecycleEvents.size(), "only the new extension activates once");
        assertTrue(lifecycleEvents.get(0).startsWith("activate:"));
    }

    @Test
    void priorityOverrideChangeReordersButReusesInstance() {
        manager.refresh(configWithPriority("1.0.0", InstanceTrackingExtension.class.getName(), 100));
        Object trackerBefore = manager.current().byId(InstanceTrackingExtension.ID);

        RefreshReport report = manager.refresh(
                configWithPriority("1.0.0", InstanceTrackingExtension.class.getName(), 10));
        log.info("priority-only change diff: {}",
                report.changes().stream().map(ExtensionChange::describe).toList());

        assertAction(report, InstanceTrackingExtension.ID, ExtensionAction.REUSED,
                ExtensionChangeReason.ORDER_ONLY_CHANGED);
        assertSame(trackerBefore, manager.current().byId(InstanceTrackingExtension.ID),
                "priority change must not rebuild the instance");
        List<String> events = InstanceTrackingExtension.EVENTS;
        assertEquals(1, events.size(), "no deactivate/reactivate on order-only change");
        assertTrue(events.get(0).startsWith("activate:"));
    }

    @Test
    void dependencyChainRebuildsDependentsWhenImplementationReplaced() {
        manager.refresh(config("1.0.0",
                BetaExtension.class.getName(), AlphaExtension.class.getName()));
        Object alphaV1 = manager.current().byId("alpha");
        Object betaV1 = manager.current().byId("beta");

        // alpha 换实现类：beta 依赖 alpha，必须连带重建，避免新旧混用
        ExtensionConfiguration replaced = config("1.0.0",
                BetaExtension.class.getName(), AlphaAlternativeExtension.class.getName());
        RefreshReport report = manager.refresh(replaced);
        log.info("input replaces alpha implementation; diff: {}",
                report.changes().stream().map(ExtensionChange::describe).toList());

        assertAction(report, "alpha", ExtensionAction.REBUILT,
                ExtensionChangeReason.IMPLEMENTATION_CHANGED);
        assertAction(report, "beta", ExtensionAction.REBUILT,
                ExtensionChangeReason.DEPENDENCY_REBUILT);

        Object alphaV2 = manager.current().byId("alpha");
        Object betaV2 = manager.current().byId("beta");
        assertTrue(alphaV1 != alphaV2, "alpha must be a new instance");
        assertTrue(betaV1 != betaV2, "beta dependent must be rebuilt");
        assertSame(AlphaAlternativeExtension.class, alphaV2.getClass());
        assertSame(BetaExtension.class, betaV2.getClass());
    }

    @Test
    void addedDependencyEdgeRebuildsAffectedExtensionAndKeepsOthers() {
        manager.refresh(config("1.0.0",
                InstanceTrackingExtension.class.getName(),
                AlphaExtension.class.getName(),
                LifecycleExtension.class.getName()));
        Object trackerBefore = manager.current().byId(InstanceTrackingExtension.ID);
        Object lifecycleBefore = manager.current().byId(LifecycleExtension.ID);

        // tracker 换成“新增了对 alpha 依赖边”的另一实现：自身重建，alpha 沿用，无关 lifecycle 沿用
        RefreshReport report = manager.refresh(config("1.0.0",
                TrackerAlphaDependentExtension.class.getName(),
                AlphaExtension.class.getName(),
                LifecycleExtension.class.getName()));
        log.info("input replaces tracker with alpha-dependent implementation; diff: {}",
                report.changes().stream().map(ExtensionChange::describe).toList());

        assertAction(report, InstanceTrackingExtension.ID, ExtensionAction.REBUILT,
                ExtensionChangeReason.IMPLEMENTATION_CHANGED);
        assertAction(report, "alpha", ExtensionAction.REUSED, ExtensionChangeReason.UNCHANGED);
        assertAction(report, LifecycleExtension.ID, ExtensionAction.REUSED,
                ExtensionChangeReason.UNCHANGED);
        assertTrue(trackerBefore != manager.current().byId(InstanceTrackingExtension.ID));
        assertSame(lifecycleBefore, manager.current().byId(LifecycleExtension.ID),
                "unrelated extension instance must be retained");
    }

    @Test
    void incrementalActivationFailureRollsBackEntirelyAndOldInstancesStayUsable() {
        manager.refresh(config("1.0.0",
                BetaExtension.class.getName(), AlphaExtension.class.getName(),
                LifecycleExtension.class.getName()));
        var snapshotBefore = manager.current();
        Object alphaBefore = snapshotBefore.byId("alpha");
        Object betaBefore = snapshotBefore.byId("beta");
        Object lifecycleBefore = snapshotBefore.byId(LifecycleExtension.ID);
        log.info("initial ids={}; submitting config that replaces beta with an activate-failing impl",
                snapshotBefore.ids());

        ExtensionConfiguration bad = config("1.0.0",
                BetaActivateFailingExtension.class.getName(), AlphaExtension.class.getName(),
                LifecycleExtension.class.getName());
        ExtensionLoadException ex = assertThrows(ExtensionLoadException.class,
                () -> manager.refresh(bad));
        log.info("incremental refresh rejected: {}",
                ex.failures().stream().map(f -> f.describe()).toList());

        assertSame(snapshotBefore, manager.current(), "old snapshot reference must remain active");
        assertEquals(List.of("alpha", "beta", "lifecycle"), manager.current().ids());
        assertSame(betaBefore, manager.current().byId("beta"), "old beta instance still in place");
        assertSame(alphaBefore, manager.current().byId("alpha"));
        assertSame(lifecycleBefore, manager.current().byId(LifecycleExtension.ID));

        // 旧实例未被停用、仍可继续提供服务；随后一个合法增量刷新照样成功
        RefreshReport next = manager.refresh(config("1.0.0",
                BetaExtension.class.getName(), AlphaExtension.class.getName(),
                LifecycleExtension.class.getName(),
                com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures
                        .InstanceTrackingExtension.class.getName()));
        assertFalse(next.rolledBack());
        assertEquals(4, manager.current().extensions().size());
        assertSame(betaBefore, manager.current().byId("beta"),
                "beta old instance reused by the next valid refresh");
    }

    @Test
    void identicalResubmissionIsIdempotentAndRebuildsNothing() {
        ExtensionConfiguration cfg = config("1.0.0",
                BetaExtension.class.getName(), AlphaExtension.class.getName(),
                LifecycleExtension.class.getName());
        manager.refresh(cfg);
        var first = manager.current();
        int eventsAfterFirst = LifecycleExtension.EVENTS.size();

        RefreshReport report = manager.refresh(cfg);
        log.info("identical config resubmitted; applied={}, changes={}", report.applied(),
                report.changes().stream().map(ExtensionChange::describe).toList());

        assertFalse(report.applied(), "identical fingerprint must be skipped");
        assertSame(first, manager.current());
        byId(report).forEach((id, change) ->
                assertEquals(ExtensionAction.REUSED, change.action(), id));
        assertEquals(eventsAfterFirst, LifecycleExtension.EVENTS.size(),
                "no lifecycle callbacks on idempotent resubmission");

        // 再来一次，仍幂等
        RefreshReport again = manager.refresh(cfg);
        assertFalse(again.applied());
        assertSame(first, manager.current());
    }

    @Test
    void concurrentReadersDuringIncrementalRefreshOnlyObserveCompleteSnapshots()
            throws Exception {
        manager.refresh(config("1.0.0",
                BetaExtension.class.getName(), AlphaExtension.class.getName()));
        ExtensionConfiguration cfgABL = config("1.0.0",
                BetaExtension.class.getName(), AlphaExtension.class.getName(),
                LifecycleExtension.class.getName());
        ExtensionConfiguration cfgAB = config("1.0.0",
                BetaExtension.class.getName(), AlphaExtension.class.getName());
        log.info("concurrent incremental writes between {} and {}; readers must see only complete sets",
                cfgAB.fingerprint(), cfgABL.fingerprint());

        int readers = 12;
        int writers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(readers + writers);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> observed = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < readers; i++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    while (!Thread.currentThread().isInterrupted()) {
                        List<String> ids = manager.current().ids();
                        observed.add(String.join(",", ids));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        for (int i = 0; i < writers; i++) {
            final ExtensionConfiguration cfg = i % 2 == 0 ? cfgABL : cfgAB;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    manager.refresh(cfg);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        for (int i = readers; i < futures.size(); i++) {
            futures.get(i).get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        Set<String> allowed = Set.of("alpha,beta", "alpha,beta,lifecycle");
        log.info("reader observed snapshots ({} samples): {}", observed.size(), observed);
        for (String ids : observed) {
            assertTrue(allowed.contains(ids),
                    "observed state must be a complete valid snapshot but was: " + ids);
        }
        assertTrue(allowed.contains(String.join(",", manager.current().ids())));
    }

    @Test
    void rollbackRestoresPreviousSuccessfulSnapshotWithItsOwnInstances() {
        ExtensionConfiguration cfg1 = config("1.0.0",
                BetaExtension.class.getName(), AlphaExtension.class.getName());
        manager.refresh(cfg1);
        var snapshotOne = manager.current();
        Object alphaAtOne = snapshotOne.byId("alpha");
        Object betaAtOne = snapshotOne.byId("beta");

        ExtensionConfiguration cfg2 = config("1.0.0",
                AlphaExtension.class.getName(), LifecycleExtension.class.getName());
        RefreshReport toTwo = manager.refresh(cfg2);
        log.info("refresh cfg2 result: {}",
                toTwo.changes().stream().map(ExtensionChange::describe).toList());
        assertEquals(List.of("alpha", "lifecycle"), manager.current().ids());

        RefreshReport back = manager.rollback();
        log.info("rollback diff: {}",
                back.changes().stream().map(ExtensionChange::describe).toList());
        assertTrue(back.rolledBack());
        assertSame(snapshotOne, manager.current(),
                "rollback restores the exact recorded snapshot instance");
        assertSame(alphaAtOne, manager.current().byId("alpha"),
                "previous alpha instance reused, not reconstructed");
        assertSame(betaAtOne, manager.current().byId("beta"),
                "previous beta instance reactivated, not reconstructed");
        assertAction(back, "beta", ExtensionAction.REBUILT,
                ExtensionChangeReason.RESTORED_FROM_PREVIOUS);
        assertAction(back, "lifecycle", ExtensionAction.REMOVED,
                ExtensionChangeReason.REMOVED_FROM_CONFIG);

        // 再次回退 = 前进到 cfg2，仍然复用当时实例
        RefreshReport forward = manager.rollback();
        assertTrue(forward.rolledBack());
        assertEquals(List.of("alpha", "lifecycle"), manager.current().ids());
    }

    @Test
    void rollbackReactivatesOldInstanceAndDeactivatesSwappedReplacement() {
        // cfg1: alpha + lifecycle（记为上一次成功快照）
        manager.refresh(config("1.0.0",
                AlphaExtension.class.getName(), LifecycleExtension.class.getName()));
        Object lifecycleV1 = manager.current().byId(LifecycleExtension.ID);

        // cfg2: 同 id 的 lifecycle 实例被移除后换成另一套（这里用 beta + alpha，lifecycle 被移除）
        LifecycleExtension.reset();
        manager.refresh(config("1.0.0",
                BetaExtension.class.getName(), AlphaExtension.class.getName()));
        // lifecycleV1 在成功切换后已被停用
        assertTrue(LifecycleExtension.EVENTS.contains("deactivate:"
                + System.identityHashCode(lifecycleV1)));

        LifecycleExtension.reset();
        RefreshReport back = manager.rollback();
        log.info("rollback over swapped set diff: {}",
                back.changes().stream().map(ExtensionChange::describe).toList());

        assertSame(lifecycleV1, manager.current().byId(LifecycleExtension.ID),
                "the exact prior lifecycle instance is restored");
        // 恢复时同一份旧实例被重新激活
        assertTrue(LifecycleExtension.EVENTS.contains("activate:"
                + System.identityHashCode(lifecycleV1)));
        // 回退后集合为 alpha + lifecycle，beta 已移除
        assertEquals(List.of("alpha", "lifecycle"), manager.current().ids());
    }

    @Test
    void rollbackWithoutPreviousSnapshotIsRejectedWith409StyleState() {
        // 启动后从未成功刷新过：没有“上一次成功配置”可回
        assertThrows(IllegalStateException.class, manager::rollback);

        manager.refresh(config("1.0.0", AlphaExtension.class.getName()));
        manager.rollback(); // 回到初始空快照（上一次成功状态）
        assertEquals(List.of(), manager.current().ids());

        // 回退本身会把切换前状态记为新的“上一次”，再回退即重新前进
        RefreshReport forward = manager.rollback();
        assertTrue(forward.rolledBack());
        assertEquals(List.of("alpha"), manager.current().ids());
    }

    @Test
    void refreshCostIsProportionalToTheActualChange() {
        manager.refresh(config("1.0.0",
                BetaExtension.class.getName(), AlphaExtension.class.getName(),
                InstanceTrackingExtension.class.getName(), LifecycleExtension.class.getName()));
        LifecycleExtension.reset();
        InstanceTrackingExtension.EVENTS.clear();

        // 只新增一个扩展：只有它产生 activate，四个旧扩展零生命周期回调
        RefreshReport report = manager.refresh(config("1.0.0",
                BetaExtension.class.getName(), AlphaExtension.class.getName(),
                InstanceTrackingExtension.class.getName(), LifecycleExtension.class.getName(),
                DeltaExtension.class.getName()));
        log.info("single-add diff: {}",
                report.changes().stream().map(ExtensionChange::describe).toList());

        assertAction(report, DeltaExtension.ID, ExtensionAction.ADDED,
                ExtensionChangeReason.NEW_ENABLED);
        assertEquals(4, report.changes().stream()
                .filter(c -> c.action() == ExtensionAction.REUSED).count());
        assertEquals(0, report.changes().stream()
                .filter(c -> c.action() == ExtensionAction.REBUILT
                        || c.action() == ExtensionAction.REMOVED).count());
        assertTrue(LifecycleExtension.EVENTS.isEmpty(),
                "retained extensions must not be deactivated/reactivated");
        assertTrue(InstanceTrackingExtension.EVENTS.isEmpty(),
                "retained extensions must not be deactivated/reactivated");
    }
}
