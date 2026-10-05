package com.github.xingjianluo.springbootpluginscaffoldjava.tests.extension;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.DefaultExtensionManager;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.DefaultExtensionRegistry;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.EnabledExtensions;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionAction;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionChange;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionConfiguration;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionDeclaration;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionLoadException;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionManager;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionPlanBuilder;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionRegistry;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.RefreshReport;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.AlphaExtension;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.DeltaExtension;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.HeavyActivateFailingExtension;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.HeavyDependentExtension;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.HeavyResourceAlternativeExtension;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.HeavyResourceExtension;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.LifecycleExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归测试：未变化的扩展在任何刷新路径下都不会被重复创建。
 * 直接统计各扩展的构造次数（= 重资源初始化次数）与生命周期事件，
 * 覆盖：只新增无关扩展、只移除一个、替换实现连带依赖方、提交被拒绝整体回退、
 * 相同配置重复提交、并发提交。每个用例打印输入配置、差异判定与处理结果。
 */
class UnchangedInstanceReuseTest {

    private static final Logger log = LoggerFactory.getLogger(UnchangedInstanceReuseTest.class);

    private ExtensionManager manager;

    private static ExtensionConfiguration config(String fw, String... classNames) {
        return ExtensionConfiguration.of(fw, Arrays.stream(classNames)
                .map(ExtensionDeclaration::of).toList());
    }

    private static List<String> diff(RefreshReport report) {
        return report.changes().stream().map(ExtensionChange::describe).toList();
    }

    private static void logCounts(String stage) {
        log.info("{}: creations heavy={}, heavyAlt={}, heavyDependent={}, heavyFailing={}; "
                        + "heavyEvents={}, lifecycleEvents={}",
                stage,
                HeavyResourceExtension.CREATIONS.get(),
                HeavyResourceAlternativeExtension.CREATIONS.get(),
                HeavyDependentExtension.CREATIONS.get(),
                HeavyActivateFailingExtension.CREATIONS.get(),
                HeavyResourceExtension.EVENTS, LifecycleExtension.EVENTS);
    }

    @BeforeEach
    void setUp() {
        ExtensionRegistry registry = new DefaultExtensionRegistry();
        registry.register(HeavyResourceExtension.class);
        registry.register(HeavyResourceAlternativeExtension.class);
        registry.register(HeavyDependentExtension.class);
        registry.register(HeavyActivateFailingExtension.class);
        registry.register(LifecycleExtension.class);
        registry.register(DeltaExtension.class);
        registry.register(AlphaExtension.class);
        HeavyResourceExtension.reset();
        HeavyResourceAlternativeExtension.reset();
        HeavyDependentExtension.reset();
        HeavyActivateFailingExtension.reset();
        LifecycleExtension.reset();
        manager = new DefaultExtensionManager(new ExtensionPlanBuilder(registry));
    }

    @Test
    void addingUnrelatedExtensionCreatesNothingElse() {
        ExtensionConfiguration first = config("1.0.0",
                HeavyResourceExtension.class.getName(),
                HeavyDependentExtension.class.getName(),
                LifecycleExtension.class.getName());
        manager.refresh(first);
        logCounts("after initial refresh");
        assertEquals(1, HeavyResourceExtension.CREATIONS.get());
        assertEquals(1, HeavyDependentExtension.CREATIONS.get());

        ExtensionConfiguration second = config("1.0.0",
                HeavyResourceExtension.class.getName(),
                HeavyDependentExtension.class.getName(),
                LifecycleExtension.class.getName(),
                DeltaExtension.class.getName());
        log.info("input[2]={} (only adds delta)", second.fingerprint());
        RefreshReport report = manager.refresh(second);
        log.info("diff result: {}", diff(report));
        logCounts("after adding unrelated delta");

        assertEquals(1, HeavyResourceExtension.CREATIONS.get(),
                "unchanged heavy extension must never be constructed again");
        assertEquals(1, HeavyDependentExtension.CREATIONS.get(),
                "unchanged dependent must never be constructed again");
        assertEquals(1, LifecycleExtension.EVENTS.size(),
                "unchanged lifecycle extension must see zero extra callbacks");
        assertEquals(2, HeavyResourceExtension.EVENTS.size(),
                "heavy must only ever see create+activate, no deactivate/re-create");
        assertTrue(report.changes().stream()
                .filter(c -> c.action() == ExtensionAction.REUSED).count() == 3);
    }

    @Test
    void removingOneExtensionCreatesNothingElse() {
        manager.refresh(config("1.0.0",
                HeavyResourceExtension.class.getName(),
                HeavyDependentExtension.class.getName(),
                LifecycleExtension.class.getName()));
        Object heavyBefore = manager.current().byId(HeavyResourceExtension.ID);
        Object dependentBefore = manager.current().byId(HeavyDependentExtension.ID);

        ExtensionConfiguration second = config("1.0.0",
                HeavyResourceExtension.class.getName(),
                HeavyDependentExtension.class.getName());
        log.info("input[2]={} (removes lifecycle only)", second.fingerprint());
        RefreshReport report = manager.refresh(second);
        log.info("diff result: {}", diff(report));
        logCounts("after removing lifecycle");

        assertEquals(1, HeavyResourceExtension.CREATIONS.get());
        assertEquals(1, HeavyDependentExtension.CREATIONS.get());
        assertSame(heavyBefore, manager.current().byId(HeavyResourceExtension.ID));
        assertSame(dependentBefore, manager.current().byId(HeavyDependentExtension.ID));
        assertEquals(List.of(HeavyResourceExtension.ID, HeavyDependentExtension.ID),
                manager.current().ids());
        // 被移除的 lifecycle 只停用一次；其余扩展零回调
        assertEquals(2, LifecycleExtension.EVENTS.size());
        assertTrue(LifecycleExtension.EVENTS.get(1).startsWith("deactivate:"));
    }

    @Test
    void replacingImplementationRebuildsOnlyTheDependentChain() {
        manager.refresh(config("1.0.0",
                HeavyResourceExtension.class.getName(),
                HeavyDependentExtension.class.getName(),
                LifecycleExtension.class.getName()));
        Object heavyV1 = manager.current().byId(HeavyResourceExtension.ID);

        ExtensionConfiguration replaced = config("1.0.0",
                HeavyResourceAlternativeExtension.class.getName(),
                HeavyDependentExtension.class.getName(),
                LifecycleExtension.class.getName());
        log.info("input[2]={} (swaps heavy implementation)", replaced.fingerprint());
        RefreshReport report = manager.refresh(replaced);
        log.info("diff result: {}", diff(report));
        logCounts("after implementation swap");

        // 被替换者与其依赖方各重建一次，无关的 lifecycle 完全不动
        assertEquals(1, HeavyResourceAlternativeExtension.CREATIONS.get(),
                "replacement implementation constructed exactly once");
        assertEquals(2, HeavyDependentExtension.CREATIONS.get(),
                "dependent rebuilt exactly once alongside its changed dependency");
        assertEquals(1, LifecycleExtension.EVENTS.size(),
                "unrelated extension must see zero callbacks");
        assertSame(HeavyResourceAlternativeExtension.class,
                manager.current().byId(HeavyResourceExtension.ID).getClass());
        assertTrue(heavyV1 != manager.current().byId(HeavyResourceExtension.ID));
        // 旧 heavy 实例在切换成功后被停用且仅停用一次
        assertTrue(HeavyResourceExtension.EVENTS.contains(
                "deactivate:" + System.identityHashCode(heavyV1)));
        assertEquals(3, HeavyResourceExtension.EVENTS.size(),
                "old heavy instance sees exactly create+activate+deactivate, nothing more");
    }

    @Test
    void rejectedByValidationDoesNotRecreateUnchangedAndRecyclesOrphans() {
        manager.refresh(config("1.0.0",
                HeavyResourceExtension.class.getName(),
                LifecycleExtension.class.getName()));
        EnabledExtensions before = manager.current();
        Object heavyBefore = before.byId(HeavyResourceExtension.ID);

        // heavy-failing 是合法新扩展（会被创建），ghost 未注册导致整单校验失败
        ExtensionConfiguration bad = config("1.0.0",
                HeavyResourceExtension.class.getName(),
                LifecycleExtension.class.getName(),
                HeavyActivateFailingExtension.class.getName(),
                "com.example.GhostExtension");
        log.info("input[2]={} (new valid extension + unregistered ghost)", bad.fingerprint());
        ExtensionLoadException ex = assertThrows(ExtensionLoadException.class,
                () -> manager.refresh(bad));
        log.info("rejected: {}", ex.failures().stream().map(f -> f.describe()).toList());
        logCounts("after validation rejection");

        assertSame(before, manager.current(), "rejected commit must not replace the snapshot");
        assertSame(heavyBefore, manager.current().byId(HeavyResourceExtension.ID));
        assertEquals(1, HeavyResourceExtension.CREATIONS.get(),
                "unchanged heavy must not be recreated by a rejected commit");
        assertEquals(1, LifecycleExtension.EVENTS.size(),
                "unchanged lifecycle must see zero callbacks from a rejected commit");

        // 本次新建但从未启用的 heavy-failing 实例必须被回收，不能泄漏资源
        assertEquals(1, HeavyActivateFailingExtension.CREATIONS.get());
        List<String> events = HeavyActivateFailingExtension.EVENTS;
        assertEquals(2, events.size(), "orphan instance must be created then recycled: " + events);
        String createdHash = events.get(0).substring("create:".length());
        assertEquals("deactivate:" + createdHash, events.get(1),
                "the never-activated orphan must be deactivated to release its resources");

        // 拒绝之后原实例照常可用：合法增量提交立即成功
        RefreshReport next = manager.refresh(config("1.0.0",
                HeavyResourceExtension.class.getName(),
                LifecycleExtension.class.getName(),
                DeltaExtension.class.getName()));
        assertTrue(next.applied());
        assertEquals(1, HeavyResourceExtension.CREATIONS.get());
        logCounts("after follow-up valid refresh");
    }

    @Test
    void rejectedByActivationFailureDoesNotRecreateUnchangedAndRecyclesOrphans() {
        manager.refresh(config("1.0.0",
                HeavyResourceExtension.class.getName(),
                LifecycleExtension.class.getName()));
        EnabledExtensions before = manager.current();
        Object heavyBefore = before.byId(HeavyResourceExtension.ID);

        ExtensionConfiguration bad = config("1.0.0",
                HeavyResourceExtension.class.getName(),
                LifecycleExtension.class.getName(),
                HeavyActivateFailingExtension.class.getName(),
                DeltaExtension.class.getName());
        log.info("input[2]={} (adds delta + activate-failing extension)", bad.fingerprint());
        ExtensionLoadException ex = assertThrows(ExtensionLoadException.class,
                () -> manager.refresh(bad));
        log.info("rejected: {}", ex.failures().stream().map(f -> f.describe()).toList());
        logCounts("after activation rejection");

        assertSame(before, manager.current(), "rejected commit must not replace the snapshot");
        assertSame(heavyBefore, manager.current().byId(HeavyResourceExtension.ID));
        assertEquals(1, HeavyResourceExtension.CREATIONS.get(),
                "unchanged heavy must not be recreated by a rejected commit");
        assertEquals(1, LifecycleExtension.EVENTS.size(),
                "unchanged lifecycle must see zero callbacks from a rejected commit");

        // 激活失败者自身被回收；排在它后面、从未被激活的 delta 实例也被回收
        List<String> failingEvents = HeavyActivateFailingExtension.EVENTS;
        assertEquals(3, failingEvents.size(),
                "create+activate(throws)+recycle expected: " + failingEvents);
        String createdHash = failingEvents.get(0).substring("create:".length());
        assertEquals("activate:" + createdHash, failingEvents.get(1));
        assertEquals("deactivate:" + createdHash, failingEvents.get(2));

        // 拒绝之后原实例照常可用
        RefreshReport next = manager.refresh(config("1.0.0",
                HeavyResourceExtension.class.getName(),
                LifecycleExtension.class.getName(),
                AlphaExtension.class.getName()));
        assertTrue(next.applied());
        assertEquals(1, HeavyResourceExtension.CREATIONS.get());
        assertEquals(1, LifecycleExtension.EVENTS.size());
        logCounts("after follow-up valid refresh");
    }

    @Test
    void identicalResubmissionCreatesAbsolutelyNothing() {
        ExtensionConfiguration cfg = config("1.0.0",
                HeavyResourceExtension.class.getName(),
                HeavyDependentExtension.class.getName(),
                LifecycleExtension.class.getName());
        manager.refresh(cfg);
        EnabledExtensions first = manager.current();

        log.info("input[2] identical fingerprint={}", cfg.fingerprint());
        RefreshReport report = manager.refresh(cfg);
        log.info("diff result: {}", diff(report));
        logCounts("after identical resubmission");

        assertSame(first, manager.current());
        assertEquals(1, HeavyResourceExtension.CREATIONS.get());
        assertEquals(1, HeavyDependentExtension.CREATIONS.get());
        assertEquals(1, LifecycleExtension.EVENTS.size());
        assertEquals(2, HeavyResourceExtension.EVENTS.size());
    }

    @Test
    void concurrentSubmissionsKeepExactlyOneInstanceOfUnchangedExtensions() throws Exception {
        ExtensionConfiguration base = config("1.0.0",
                HeavyResourceExtension.class.getName(),
                LifecycleExtension.class.getName());
        manager.refresh(base);

        ExtensionConfiguration withDelta = config("1.0.0",
                HeavyResourceExtension.class.getName(),
                LifecycleExtension.class.getName(),
                DeltaExtension.class.getName());
        ExtensionConfiguration withAlpha = config("1.0.0",
                HeavyResourceExtension.class.getName(),
                LifecycleExtension.class.getName(),
                AlphaExtension.class.getName());
        log.info("concurrent inputs: base={}, withDelta={}, withAlpha={}",
                base.fingerprint(), withDelta.fingerprint(), withAlpha.fingerprint());

        int writers = 12;
        int rounds = 5;
        ExecutorService pool = Executors.newFixedThreadPool(writers + 4);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> observed = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < 4; i++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    while (!Thread.currentThread().isInterrupted()) {
                        observed.add(String.join(",", manager.current().ids()));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        ExtensionConfiguration[] configs = {base, withDelta, withAlpha};
        for (int i = 0; i < writers; i++) {
            final int offset = i;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int r = 0; r < rounds; r++) {
                        manager.refresh(configs[(offset + r) % configs.length]);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        for (int i = 4; i < futures.size(); i++) {
            futures.get(i).get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        logCounts("after concurrent submissions");

        // 无论并发如何交错，未变化的 heavy/lifecycle 从头到尾只有一份实例
        assertEquals(1, HeavyResourceExtension.CREATIONS.get(),
                "heavy must be constructed exactly once across all concurrent refreshes");
        assertEquals(1, LifecycleExtension.EVENTS.size(),
                "lifecycle must see exactly one activate and nothing else");
        assertEquals(2, HeavyResourceExtension.EVENTS.size(),
                "heavy must only ever see create+activate");

        // 读者只能看到合法完整快照；最终生效的也是某个合法完整快照
        Set<String> allowed = Set.of(
                "heavy,lifecycle", "heavy,lifecycle,delta", "heavy,lifecycle,alpha");
        for (String ids : observed) {
            assertTrue(allowed.contains(ids),
                    "observed state must be a complete valid snapshot but was: " + ids);
        }
        assertTrue(allowed.contains(String.join(",", manager.current().ids())));
        assertSame(HeavyResourceExtension.class,
                manager.current().byId(HeavyResourceExtension.ID).getClass());
    }
}
