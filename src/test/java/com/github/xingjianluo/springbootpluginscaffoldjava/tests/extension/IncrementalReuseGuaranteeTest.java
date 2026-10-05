package com.github.xingjianluo.springbootpluginscaffoldjava.tests.extension;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.DefaultExtensionManager;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.DefaultExtensionRegistry;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionChange;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionConfiguration;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionDeclaration;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionLoadException;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionManager;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionPlanBuilder;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionRegistry;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.RefreshReport;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.HeavyActivateFailingExtension;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.HeavyConsumerExtension;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.HeavyCoreExtension;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.HeavyCoreV2Extension;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.HeavyLateExtension;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.HeavyResourceExtension;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.ResourceLifecycleTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
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
 * 回归守卫：带重资源初始化（构造即建连接池/缓存/线程）的扩展，
 * 在被判为“沿用(REUSED)”时构造器调用次数必须严格保持为零增量——
 * 创建/初始化在其整个生命周期内只发生一次。
 * 覆盖：只新增无关扩展、只移除一个、替换实现连带依赖方、校验拒绝、激活拒绝、
 * 相同配置重复提交、并发提交。每个用例打印输入配置、差异判定与处理结果。
 */
class IncrementalReuseGuaranteeTest {

    private static final Logger log = LoggerFactory.getLogger(IncrementalReuseGuaranteeTest.class);

    private ExtensionManager manager;

    private static ExtensionConfiguration config(String fw, String... classNames) {
        return ExtensionConfiguration.of(fw, Arrays.stream(classNames)
                .map(ExtensionDeclaration::of).toList());
    }

    private static String names(Class<?>... types) {
        return Arrays.toString(Arrays.stream(types).map(Class::getSimpleName).toArray());
    }

    private static void logReport(String tag, ExtensionConfiguration input, RefreshReport report) {
        log.info("{} input={} applied={}", tag, input.fingerprint(), report.applied());
        report.changes().forEach(change -> log.info("{} diff: {}", tag, change.describe()));
    }

    @BeforeEach
    void setUp() {
        ExtensionRegistry registry = new DefaultExtensionRegistry();
        registry.register(HeavyResourceExtension.class);
        registry.register(HeavyCoreExtension.class);
        registry.register(HeavyCoreV2Extension.class);
        registry.register(HeavyConsumerExtension.class);
        registry.register(HeavyLateExtension.class);
        registry.register(HeavyActivateFailingExtension.class);
        ResourceLifecycleTracker.reset();
        manager = new DefaultExtensionManager(new ExtensionPlanBuilder(registry));
    }

    @Test
    void unrelatedAdditionNeverRecreatesUnchangedExtensions() {
        ExtensionConfiguration first = config("1.0.0",
                HeavyResourceExtension.class.getName(),
                HeavyCoreExtension.class.getName(),
                HeavyConsumerExtension.class.getName());
        manager.refresh(first);
        Object resourceBefore = manager.current().byId(HeavyResourceExtension.ID);
        Object coreBefore = manager.current().byId(HeavyCoreExtension.ID);
        Object consumerBefore = manager.current().byId(HeavyConsumerExtension.ID);
        log.info("baseline created: {}, {}, {}",
                ResourceLifecycleTracker.describe(HeavyResourceExtension.class),
                ResourceLifecycleTracker.describe(HeavyCoreExtension.class),
                ResourceLifecycleTracker.describe(HeavyConsumerExtension.class));

        ExtensionConfiguration second = config("1.0.0",
                HeavyResourceExtension.class.getName(),
                HeavyCoreExtension.class.getName(),
                HeavyConsumerExtension.class.getName(),
                HeavyLateExtension.class.getName());
        RefreshReport report = manager.refresh(second);
        logReport("add-unrelated", second, report);

        assertTrue(report.applied());
        // 未变化扩展：构造器零增量，生命周期回调零增量，运行实例就是原来那一份
        assertEquals(1, ResourceLifecycleTracker.created(HeavyResourceExtension.class),
                "unchanged heavy-resource must be constructed exactly once in its lifetime");
        assertEquals(1, ResourceLifecycleTracker.created(HeavyCoreExtension.class));
        assertEquals(1, ResourceLifecycleTracker.created(HeavyConsumerExtension.class));
        assertEquals(1, ResourceLifecycleTracker.activated(HeavyResourceExtension.class));
        assertEquals(0, ResourceLifecycleTracker.deactivated(HeavyResourceExtension.class));
        assertEquals(1, ResourceLifecycleTracker.activated(HeavyCoreExtension.class));
        assertEquals(0, ResourceLifecycleTracker.deactivated(HeavyCoreExtension.class));
        assertEquals(1, ResourceLifecycleTracker.activated(HeavyConsumerExtension.class));
        assertEquals(0, ResourceLifecycleTracker.deactivated(HeavyConsumerExtension.class));
        assertSame(resourceBefore, manager.current().byId(HeavyResourceExtension.ID));
        assertSame(coreBefore, manager.current().byId(HeavyCoreExtension.ID));
        assertSame(consumerBefore, manager.current().byId(HeavyConsumerExtension.ID));
        // 新增者只创建并激活一次
        assertEquals(1, ResourceLifecycleTracker.created(HeavyLateExtension.class));
        assertEquals(1, ResourceLifecycleTracker.activated(HeavyLateExtension.class));
    }

    @Test
    void removalNeverRecreatesSurvivors() {
        manager.refresh(config("1.0.0",
                HeavyResourceExtension.class.getName(),
                HeavyCoreExtension.class.getName(),
                HeavyLateExtension.class.getName()));
        Object resourceBefore = manager.current().byId(HeavyResourceExtension.ID);

        ExtensionConfiguration second = config("1.0.0",
                HeavyResourceExtension.class.getName(),
                HeavyCoreExtension.class.getName());
        RefreshReport report = manager.refresh(second);
        logReport("remove-one", second, report);

        assertEquals(1, ResourceLifecycleTracker.created(HeavyResourceExtension.class),
                "survivor must not be reconstructed when a sibling is removed");
        assertEquals(1, ResourceLifecycleTracker.created(HeavyCoreExtension.class));
        assertEquals(0, ResourceLifecycleTracker.deactivated(HeavyResourceExtension.class));
        assertEquals(0, ResourceLifecycleTracker.deactivated(HeavyCoreExtension.class));
        assertSame(resourceBefore, manager.current().byId(HeavyResourceExtension.ID));
        // 被移除者只停用一次，且只创建过一次
        assertEquals(1, ResourceLifecycleTracker.created(HeavyLateExtension.class));
        assertEquals(1, ResourceLifecycleTracker.deactivated(HeavyLateExtension.class));
        assertEquals(0, ResourceLifecycleTracker.live(HeavyLateExtension.class));
    }

    @Test
    void implementationSwapRebuildsDependentsOnceAndSparesUnrelated() {
        manager.refresh(config("1.0.0",
                HeavyResourceExtension.class.getName(),
                HeavyCoreExtension.class.getName(),
                HeavyConsumerExtension.class.getName()));
        Object resourceBefore = manager.current().byId(HeavyResourceExtension.ID);

        // heavy-core 换实现：依赖它的 heavy-consumer 连带重建（恰好一次），
        // 无关的 heavy-resource 完全不动
        ExtensionConfiguration swapped = config("1.0.0",
                HeavyResourceExtension.class.getName(),
                HeavyCoreV2Extension.class.getName(),
                HeavyConsumerExtension.class.getName());
        RefreshReport report = manager.refresh(swapped);
        logReport("swap-implementation", swapped, report);

        assertEquals(1, ResourceLifecycleTracker.created(HeavyCoreExtension.class));
        assertEquals(1, ResourceLifecycleTracker.created(HeavyCoreV2Extension.class),
                "replacement core constructed exactly once");
        assertEquals(2, ResourceLifecycleTracker.created(HeavyConsumerExtension.class),
                "dependent rebuilt exactly once (old + new), not more");
        assertEquals(1, ResourceLifecycleTracker.deactivated(HeavyCoreExtension.class),
                "old core instance retired exactly once");
        assertEquals(1, ResourceLifecycleTracker.deactivated(HeavyConsumerExtension.class),
                "old dependent instance retired exactly once");
        assertEquals(1, ResourceLifecycleTracker.created(HeavyResourceExtension.class),
                "unrelated extension must not be reconstructed by a swap elsewhere");
        assertEquals(0, ResourceLifecycleTracker.deactivated(HeavyResourceExtension.class));
        assertSame(resourceBefore, manager.current().byId(HeavyResourceExtension.ID));
        assertSame(HeavyCoreV2Extension.class,
                manager.current().byId(HeavyCoreExtension.ID).getClass());
    }

    @Test
    void validationRejectionCreatesNothingExtraAndLeavesNoOrphans() {
        manager.refresh(config("1.0.0", HeavyResourceExtension.class.getName()));
        var snapshotBefore = manager.current();
        Object resourceBefore = snapshotBefore.byId(HeavyResourceExtension.ID);

        // 配置里混了一个未登记的实现：整体拒绝；同配置里可创建的 heavy-late
        // 即使被建出来也必须被回收，不能留下占用资源的孤儿实例
        ExtensionConfiguration bad = config("1.0.0",
                HeavyResourceExtension.class.getName(),
                HeavyLateExtension.class.getName(),
                "com.example.GhostExtension");
        log.info("reject-validation input={}", bad.fingerprint());
        ExtensionLoadException ex = assertThrows(ExtensionLoadException.class,
                () -> manager.refresh(bad));
        log.info("reject-validation failures={}",
                ex.failures().stream().map(f -> f.describe()).toList());

        assertEquals(1, ResourceLifecycleTracker.created(HeavyResourceExtension.class),
                "rejected submission must not recreate unchanged extensions");
        assertEquals(0, ResourceLifecycleTracker.deactivated(HeavyResourceExtension.class));
        assertEquals(1, ResourceLifecycleTracker.created(HeavyLateExtension.class));
        assertEquals(0, ResourceLifecycleTracker.activated(HeavyLateExtension.class));
        assertEquals(1, ResourceLifecycleTracker.deactivated(HeavyLateExtension.class),
                "instance created during a rejected build must be disposed, not leaked");
        assertEquals(0, ResourceLifecycleTracker.live(HeavyLateExtension.class));
        assertSame(snapshotBefore, manager.current());
        assertSame(resourceBefore, manager.current().byId(HeavyResourceExtension.ID));
    }

    @Test
    void activationRejectionDisposesNeverActivatedInstancesAndKeepsOldState() {
        manager.refresh(config("1.0.0", HeavyResourceExtension.class.getName()));
        var snapshotBefore = manager.current();
        Object resourceBefore = snapshotBefore.byId(HeavyResourceExtension.ID);

        // heavy-failing 激活必炸；heavy-late 排在它后面：建出来了却永远没机会激活
        ExtensionConfiguration bad = config("1.0.0",
                HeavyResourceExtension.class.getName(),
                HeavyActivateFailingExtension.class.getName(),
                HeavyLateExtension.class.getName());
        log.info("reject-activation input={}", bad.fingerprint());
        ExtensionLoadException ex = assertThrows(ExtensionLoadException.class,
                () -> manager.refresh(bad));
        log.info("reject-activation failures={}",
                ex.failures().stream().map(f -> f.describe()).toList());

        // 未变化扩展：零重建、零回调
        assertEquals(1, ResourceLifecycleTracker.created(HeavyResourceExtension.class));
        assertEquals(0, ResourceLifecycleTracker.deactivated(HeavyResourceExtension.class));
        // 激活失败者与“建出来没启用”的实例都被回收，无孤儿
        assertEquals(1, ResourceLifecycleTracker.created(HeavyActivateFailingExtension.class));
        assertEquals(1, ResourceLifecycleTracker.deactivated(HeavyActivateFailingExtension.class));
        assertEquals(0, ResourceLifecycleTracker.live(HeavyActivateFailingExtension.class));
        assertEquals(1, ResourceLifecycleTracker.created(HeavyLateExtension.class));
        assertEquals(0, ResourceLifecycleTracker.activated(HeavyLateExtension.class),
                "heavy-late sits after the failing one and must never be activated");
        assertEquals(1, ResourceLifecycleTracker.deactivated(HeavyLateExtension.class),
                "created-but-never-activated instance must be disposed");
        assertEquals(0, ResourceLifecycleTracker.live(HeavyLateExtension.class));
        assertSame(snapshotBefore, manager.current());
        assertSame(resourceBefore, manager.current().byId(HeavyResourceExtension.ID));

        // 拒绝之后原实例照常可用：后续合法刷新继续沿用同一份实例
        RefreshReport next = manager.refresh(config("1.0.0",
                HeavyResourceExtension.class.getName(), HeavyLateExtension.class.getName()));
        logReport("after-rejection", config("1.0.0",
                HeavyResourceExtension.class.getName(), HeavyLateExtension.class.getName()), next);
        assertSame(resourceBefore, manager.current().byId(HeavyResourceExtension.ID));
        assertEquals(1, ResourceLifecycleTracker.created(HeavyResourceExtension.class));
        assertEquals(1, ResourceLifecycleTracker.live(HeavyLateExtension.class),
                "exactly one live heavy-late instance after the valid follow-up refresh");
    }

    @Test
    void identicalResubmissionCreatesNothing() {
        ExtensionConfiguration cfg = config("1.0.0",
                HeavyResourceExtension.class.getName(),
                HeavyCoreExtension.class.getName());
        manager.refresh(cfg);
        var first = manager.current();

        RefreshReport again = manager.refresh(cfg);
        logReport("idempotent", cfg, again);

        assertFalse(again.applied());
        assertSame(first, manager.current());
        assertEquals(1, ResourceLifecycleTracker.created(HeavyResourceExtension.class));
        assertEquals(1, ResourceLifecycleTracker.created(HeavyCoreExtension.class));
        assertEquals(1, ResourceLifecycleTracker.activated(HeavyResourceExtension.class));
        assertEquals(0, ResourceLifecycleTracker.deactivated(HeavyResourceExtension.class));
    }

    @Test
    void concurrentSubmissionsCreateUnchangedExtensionsOnlyOnce() throws Exception {
        ExtensionConfiguration base = config("1.0.0",
                HeavyResourceExtension.class.getName(), HeavyCoreExtension.class.getName());
        manager.refresh(base);
        ExtensionConfiguration withLate = config("1.0.0",
                HeavyResourceExtension.class.getName(), HeavyCoreExtension.class.getName(),
                HeavyLateExtension.class.getName());
        log.info("concurrent inputs: A={} B={}", base.fingerprint(), withLate.fingerprint());

        int writers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            final ExtensionConfiguration cfg = i % 2 == 0 ? withLate : base;
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
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        List<String> finalIds = manager.current().ids();
        log.info("concurrent result: finalIds={}, {}, {}, {}", finalIds,
                ResourceLifecycleTracker.describe(HeavyResourceExtension.class),
                ResourceLifecycleTracker.describe(HeavyCoreExtension.class),
                ResourceLifecycleTracker.describe(HeavyLateExtension.class));

        // 最终生效的是某个合法完整快照
        Set<List<String>> allowed = Set.of(
                List.of(HeavyResourceExtension.ID, HeavyCoreExtension.ID),
                List.of(HeavyResourceExtension.ID, HeavyCoreExtension.ID, HeavyLateExtension.ID));
        assertTrue(allowed.contains(finalIds), "final snapshot must be a valid complete one");
        // 未变化扩展全程只创建一次、零停用
        assertEquals(1, ResourceLifecycleTracker.created(HeavyResourceExtension.class));
        assertEquals(1, ResourceLifecycleTracker.activated(HeavyResourceExtension.class));
        assertEquals(0, ResourceLifecycleTracker.deactivated(HeavyResourceExtension.class));
        assertEquals(1, ResourceLifecycleTracker.created(HeavyCoreExtension.class));
        assertEquals(0, ResourceLifecycleTracker.deactivated(HeavyCoreExtension.class));
        // 变化的扩展：每次创建都对应恰好一次激活，且最终无孤儿
        assertEquals(ResourceLifecycleTracker.activated(HeavyLateExtension.class),
                ResourceLifecycleTracker.created(HeavyLateExtension.class));
        int expectedLive = finalIds.contains(HeavyLateExtension.ID) ? 1 : 0;
        assertEquals(expectedLive, ResourceLifecycleTracker.live(HeavyLateExtension.class),
                "no orphaned heavy-late instances after concurrent submissions");
    }
}
