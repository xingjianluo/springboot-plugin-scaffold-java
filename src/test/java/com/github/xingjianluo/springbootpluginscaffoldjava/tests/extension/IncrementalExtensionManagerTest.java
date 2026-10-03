package com.github.xingjianluo.springbootpluginscaffoldjava.tests.extension;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.DefaultExtensionManager;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.DefaultExtensionRegistry;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.EnabledExtensions;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionAction;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionConfiguration;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionDeclaration;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionDecision;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionManager;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionPlanBuilder;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionRegistry;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.RefreshReport;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.ActivateFailingExtension;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.AlphaExtension;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.AltAlphaExtension;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.BetaExtension;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.DeltaExtension;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.LifecycleExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 增量热更新专项测试：实例复用、依赖连带重建、失败整体回退（旧实例仍可用）、
 * 重复提交幂等、并发读写只观察到完整快照、回退到上一次成功配置。
 * 每个用例打印输入配置、差异判定与处理结果。
 */
class IncrementalExtensionManagerTest {

    private static final Logger log = LoggerFactory.getLogger(IncrementalExtensionManagerTest.class);

    private ExtensionRegistry registry;
    private ExtensionManager manager;

    private static ExtensionConfiguration config(String fw, String... classNames) {
        return ExtensionConfiguration.of(fw, Arrays.stream(classNames)
                .map(ExtensionDeclaration::of).toList());
    }

    private static void logReport(String title, RefreshReport report) {
        log.info("{}: mode={}, skipped={}, snapshotIds={}", title, report.mode(),
                report.skipped(), report.snapshotIds());
        report.decisions().forEach(d -> log.info("  diff decision: id={}, action={}, reason={}",
                d.id(), d.action(), d.reason()));
    }

    private Map<String, ExtensionDecision> byId(RefreshReport report) {
        Map<String, ExtensionDecision> map = new java.util.LinkedHashMap<>();
        report.decisions().forEach(d -> map.put(d.id(), d));
        return map;
    }

    @BeforeEach
    void setUp() {
        registry = new DefaultExtensionRegistry();
        registry.register(AlphaExtension.class);
        registry.register(AltAlphaExtension.class);
        registry.register(BetaExtension.class);
        registry.register(DeltaExtension.class);
        registry.register(com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.EpsilonExtension.class);
        registry.register(LifecycleExtension.class);
        registry.register(ActivateFailingExtension.class);
        LifecycleExtension.reset();
        manager = new DefaultExtensionManager(new ExtensionPlanBuilder(registry));
    }

    @Test
    void addingUnrelatedExtensionReusesAllRunningInstances() {
        manager.refresh(config("1.0.0",
                LifecycleExtension.class.getName(),
                AlphaExtension.class.getName(),
                BetaExtension.class.getName(),
                DeltaExtension.class.getName()));
        EnabledExtensions before = manager.current();
        Object lifecycle = before.byId("lifecycle");
        Object alpha = before.byId("alpha");
        Object beta = before.byId("beta");
        Object delta = before.byId("delta");
        int eventCount = LifecycleExtension.EVENTS.size();
        log.info("input config #1: [lifecycle, alpha, beta(->alpha), delta]; running instances captured");

        // 仅新增一个与已有扩展均无依赖关系的独立扩展
        ExtensionConfiguration cfg2 = configWithStandalone(
                LifecycleExtension.class.getName(),
                AlphaExtension.class.getName(),
                BetaExtension.class.getName(),
                DeltaExtension.class.getName());

        RefreshReport report = manager.refresh(cfg2);
        logReport("adding an unrelated extension", report);

        EnabledExtensions after = manager.current();
        assertSame(lifecycle, after.byId("lifecycle"), "lifecycle instance must be reused");
        assertSame(alpha, after.byId("alpha"), "alpha instance must be reused");
        assertSame(beta, after.byId("beta"), "beta instance must be reused");
        assertSame(delta, after.byId("delta"), "unrelated delta instance must be reused");
        Map<String, ExtensionDecision> decisions = byId(report);
        assertEquals(ExtensionAction.ADDED, decisions.get("standalone").action());
        assertEquals(ExtensionAction.RETAINED, decisions.get("lifecycle").action());
        assertEquals(ExtensionAction.RETAINED, decisions.get("alpha").action());
        assertEquals(ExtensionAction.RETAINED, decisions.get("beta").action());
        assertEquals(ExtensionAction.RETAINED, decisions.get("delta").action());
        // 沿用实例没有再次 activate
        long extraActivates = LifecycleExtension.EVENTS.subList(eventCount,
                LifecycleExtension.EVENTS.size()).stream().filter(e -> e.startsWith("activate:")).count();
        assertEquals(0, extraActivates, "reused instances must not be activated again");
    }

    /** 通过内联的已注册独立扩展构造“新增无关扩展”配置。 */
    private ExtensionConfiguration configWithStandalone(String... classNames) {
        // 注册一个一次性独立扩展
        String standaloneName = StandaloneExtension.class.getName();
        ((DefaultExtensionRegistry) registry).register(StandaloneExtension.class);
        List<String> all = new ArrayList<>(Arrays.asList(classNames));
        all.add(standaloneName);
        return config("1.0.0", all.toArray(String[]::new));
    }

    @Test
    void replacingImplementationRebuildsItAndAllTransitiveDependentsOnly() {
        manager.refresh(config("1.0.0",
                LifecycleExtension.class.getName(),
                AlphaExtension.class.getName(),
                BetaExtension.class.getName(),
                DeltaExtension.class.getName()));
        EnabledExtensions before = manager.current();
        Object oldLifecycle = before.byId("lifecycle");
        Object oldAlpha = before.byId("alpha");
        Object oldBeta = before.byId("beta");
        Object oldDelta = before.byId("delta");
        log.info("input config #1: [lifecycle, alpha, beta(->alpha), delta]; "
                + "config #2 replaces alpha class with AltAlpha (same id)");

        RefreshReport report = manager.refresh(config("1.0.0",
                LifecycleExtension.class.getName(),
                AltAlphaExtension.class.getName(),
                BetaExtension.class.getName(),
                DeltaExtension.class.getName()));
        logReport("implementation replaced, cascade expected", report);

        EnabledExtensions after = manager.current();
        assertEquals(AltAlphaExtension.class, after.byId("alpha").getClass());
        assertFalse(oldAlpha == after.byId("alpha"), "alpha must be a fresh instance");
        assertFalse(oldBeta == after.byId("beta"),
                "beta depends on alpha and must be rebuilt in cascade");
        assertSame(oldLifecycle, after.byId("lifecycle"),
                "lifecycle is unrelated and must keep the same instance");
        assertSame(oldDelta, after.byId("delta"),
                "delta is unrelated and must keep the same instance");

        Map<String, ExtensionDecision> decisions = byId(report);
        assertEquals(ExtensionAction.RECREATED, decisions.get("alpha").action());
        assertEquals(ExtensionAction.RECREATED, decisions.get("beta").action());
        assertTrue(decisions.get("beta").reason().contains("cascade"),
                "beta reason must explain the dependency cascade: " + decisions.get("beta").reason());
        assertEquals(ExtensionAction.RETAINED, decisions.get("lifecycle").action());
        assertEquals(ExtensionAction.RETAINED, decisions.get("delta").action());

        // 不能出现新旧混用：新快照中的 alpha 必须就是 AltAlpha
        assertEquals(Set.of("lifecycle", "alpha", "beta", "delta"),
                Set.copyOf(after.ids()));
    }

    @Test
    void failedIncrementalActivationRollsBackAndOldInstancesStayUsable() {
        manager.refresh(config("1.0.0",
                AlphaExtension.class.getName(),
                BetaExtension.class.getName(),
                DeltaExtension.class.getName()));
        EnabledExtensions before = manager.current();
        Object alpha = before.byId("alpha");
        Object beta = before.byId("beta");
        Object delta = before.byId("delta");
        log.info("input config #1: [alpha, beta, delta] active; config #2 adds activate-failing "
                + "(depends on alpha), expected rejection and full rollback");

        var ex = assertThrows(com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionLoadException.class,
                () -> manager.refresh(config("1.0.0",
                        AlphaExtension.class.getName(),
                        BetaExtension.class.getName(),
                        DeltaExtension.class.getName(),
                        ActivateFailingExtension.class.getName())));
        log.info("decision: rejected with {}", ex.failures());

        assertSame(before, manager.current(), "active snapshot reference must stay the old one");
        assertSame(alpha, manager.current().byId("alpha"), "old alpha stays the same instance");
        assertSame(beta, manager.current().byId("beta"), "old beta stays the same instance");
        assertSame(delta, manager.current().byId("delta"), "old delta stays the same instance");
        assertEquals(List.of("delta", "alpha", "beta"), manager.current().ids());

        // 回退后旧实例仍可继续正常使用：再做一次合法增量刷新应成功
        RefreshReport recovery = manager.refresh(config("1.0.0",
                AlphaExtension.class.getName(),
                BetaExtension.class.getName(),
                DeltaExtension.class.getName(),
                LifecycleExtension.class.getName()));
        logReport("recovery refresh after rollback", recovery);
        assertEquals(List.of("delta", "alpha", "beta", "lifecycle"),
                manager.current().ids());
        assertSame(alpha, manager.current().byId("alpha"));
    }

    @Test
    void identicalSubmissionIsIdempotentWithoutAnyRebuild() {
        ExtensionConfiguration cfg = config("1.0.0",
                BetaExtension.class.getName(), AlphaExtension.class.getName(),
                DeltaExtension.class.getName());
        manager.refresh(cfg);
        EnabledExtensions first = manager.current();
        Object alpha = first.byId("alpha");
        Object beta = first.byId("beta");
        Object delta = first.byId("delta");
        int events = LifecycleExtension.EVENTS.size();
        log.info("input config submitted twice (identical fingerprint): {}", cfg.fingerprint());

        RefreshReport report = manager.refresh(cfg);
        logReport("identical resubmission", report);

        assertTrue(report.skipped(), "report must be flagged as skipped");
        assertSame(first, manager.current(), "snapshot reference must not change");
        assertSame(alpha, manager.current().byId("alpha"));
        assertSame(beta, manager.current().byId("beta"));
        assertSame(delta, manager.current().byId("delta"));
        assertEquals(LifecycleExtension.EVENTS.size(), events,
                "no lifecycle callback may happen on idempotent submit");
        report.decisions().forEach(d ->
                assertEquals(ExtensionAction.RETAINED, d.action()));
    }

    @Test
    void concurrentRefreshesAndReadsObserveOnlyCompleteSnapshots() throws Exception {
        manager.refresh(config("1.0.0",
                AlphaExtension.class.getName(), DeltaExtension.class.getName()));

        ExtensionConfiguration cfgA = config("1.0.0",
                DeltaExtension.class.getName(),
                AlphaExtension.class.getName());
        ExtensionConfiguration cfgB = config("1.0.0",
                DeltaExtension.class.getName(),
                AlphaExtension.class.getName(),
                BetaExtension.class.getName());
        log.info("concurrent input configs: A={}, B={}; readers must never observe a partial set, "
                + "and delta identity must remain stable across both", cfgA.fingerprint(), cfgB.fingerprint());

        int writers = 8;
        int readers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(writers + readers);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> observed = new ConcurrentLinkedQueue<>();
        ConcurrentHashMap<String, Boolean> deltaIdentities = new ConcurrentHashMap<>();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < writers; i++) {
            ExtensionConfiguration cfg = i % 2 == 0 ? cfgB : cfgA;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    manager.refresh(cfg);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        for (int i = 0; i < readers; i++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int n = 0; n < 200; n++) {
                        EnabledExtensions snapshot = manager.current();
                        List<String> ids = snapshot.ids();
                        observed.add(String.join(",", ids));
                        // 每个快照内部必须自洽：beta 若存在，alpha 必须同时存在且顺序在前
                        if (ids.contains("beta")) {
                            assertTrue(ids.indexOf("alpha") >= 0
                                    && ids.indexOf("alpha") < ids.indexOf("beta"));
                        }
                        deltaIdentities.putIfAbsent(
                                Integer.toHexString(System.identityHashCode(snapshot.byId("delta"))),
                                Boolean.TRUE);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        log.info("observed snapshot sequences (sample)={}", observed.stream().limit(20).toList());
        Set<String> allowed = Set.of("delta,alpha", "delta,alpha,beta");
        for (String ids : observed) {
            assertTrue(allowed.contains(ids),
                    "observed state must be a complete valid snapshot but was: " + ids);
        }
        // delta 在两份配置中都未变化，自始至终只有一个实例
        assertEquals(1, deltaIdentities.size(),
                "delta must be the same instance across all observed snapshots");
        String finalIds = String.join(",", manager.current().ids());
        assertTrue(allowed.contains(finalIds));
    }

    @Test
    void restoreReactivatesPreviousSuccessfulSnapshotInstances() {
        manager.refresh(config("1.0.0",
                AlphaExtension.class.getName(),
                BetaExtension.class.getName(),
                DeltaExtension.class.getName()));
        EnabledExtensions first = manager.current();
        Object alpha1 = first.byId("alpha");
        Object beta1 = first.byId("beta");
        Object delta1 = first.byId("delta");

        manager.refresh(config("1.0.0",
                AlphaExtension.class.getName(),
                DeltaExtension.class.getName(),
                LifecycleExtension.class.getName()));
        EnabledExtensions second = manager.current();
        Object alpha2 = second.byId("alpha");
        Object delta2 = second.byId("delta");
        log.info("config #1 ids=[alpha,beta,delta], config #2 ids=[alpha,delta,lifecycle]; "
                + "alpha/delta unchanged so shared, beta removed, lifecycle added; now restoring");

        RefreshReport report = manager.restore();
        logReport("restore to previous successful snapshot", report);

        EnabledExtensions restored = manager.current();
        assertEquals(List.of("delta", "alpha", "beta"), restored.ids());
        assertSame(alpha1, restored.byId("alpha"), "restore must reuse the previous snapshot alpha");
        assertSame(beta1, restored.byId("beta"), "restore must bring back the very same beta instance");
        assertSame(delta1, restored.byId("delta"), "restore must reuse the previous snapshot delta");
        Map<String, ExtensionDecision> decisions = byId(report);
        assertEquals(ExtensionAction.RESTORED, decisions.get("beta").action());
        assertEquals(ExtensionAction.REMOVED, decisions.get("lifecycle").action());

        // 连续再回退一次等价于在两份成功配置间来回切换
        RefreshReport back = manager.restore();
        logReport("restore again toggles forward", back);
        assertEquals(List.of("delta", "alpha", "lifecycle"), manager.current().ids());
        assertSame(alpha2, manager.current().byId("alpha"));
        assertSame(delta2, manager.current().byId("delta"));
    }

    @Test
    void restoreWithoutPreviousSnapshotIsRejectedAndLeavesStateUntouched() {
        EnabledExtensions empty = manager.current();
        log.info("restore on a manager that only has the initial empty snapshot");
        assertThrows(IllegalStateException.class, manager::restore);
        assertSame(empty, manager.current());
    }

    @Test
    void cascadeRebuildPropagatesTwoHopsUpTheDependencyChain() {
        // 依赖链 alpha -> beta -> epsilon；delta 独立
        manager.refresh(config("1.0.0",
                AlphaExtension.class.getName(),
                BetaExtension.class.getName(),
                com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.EpsilonExtension.class.getName(),
                DeltaExtension.class.getName()));
        EnabledExtensions before = manager.current();
        Object oldAlpha = before.byId("alpha");
        Object oldBeta = before.byId("beta");
        Object oldEpsilon = before.byId("epsilon");
        Object oldDelta = before.byId("delta");
        log.info("chain alpha->beta->epsilon plus independent delta; swapping alpha's class, "
                + "expect beta and epsilon rebuilt transitively, delta retained");

        RefreshReport report = manager.refresh(config("1.0.0",
                AltAlphaExtension.class.getName(),
                BetaExtension.class.getName(),
                com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.EpsilonExtension.class.getName(),
                DeltaExtension.class.getName()));
        logReport("two-hop transitive cascade", report);

        EnabledExtensions after = manager.current();
        assertFalse(oldAlpha == after.byId("alpha"));
        assertFalse(oldBeta == after.byId("beta"), "direct dependent must rebuild");
        assertFalse(oldEpsilon == after.byId("epsilon"),
                "dependent of a rebuilt dependent must rebuild transitively");
        assertSame(oldDelta, after.byId("delta"), "independent extension must be untouched");

        Map<String, ExtensionDecision> decisions = byId(report);
        assertEquals(ExtensionAction.RECREATED, decisions.get("alpha").action());
        assertEquals(ExtensionAction.RECREATED, decisions.get("beta").action());
        assertEquals(ExtensionAction.RECREATED, decisions.get("epsilon").action());
        assertEquals(ExtensionAction.RETAINED, decisions.get("delta").action());
        assertTrue(decisions.get("epsilon").reason().contains("beta"),
                "epsilon reason must name beta as the changed dependency");
    }

    /** 一次性独立扩展：无任何依赖，用于“新增无关扩展”场景。 */
    public static class StandaloneExtension implements com.github.xingjianluo.springbootpluginscaffoldjava.extension.Extension {
        @Override
        public String id() {
            return "standalone";
        }

        @Override
        public int priority() {
            return 90;
        }
    }
}
