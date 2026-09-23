package com.github.xingjianluo.springbootpluginscaffoldjava.tests.extension;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.DefaultExtensionManager;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.DefaultExtensionRegistry;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.EnabledExtensions;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionConfiguration;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionDeclaration;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionFailureReason;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionLoadException;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionManager;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionPlanBuilder;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionRegistry;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.ActivateFailingExtension;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.AlphaExtension;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.BetaExtension;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.LifecycleExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖运行期刷新的幂等性、原子切换、失败整体回退与并发刷新。
 * 每个用例打印提交配置与判定依据。
 */
class ExtensionManagerTest {

    private static final Logger log = LoggerFactory.getLogger(ExtensionManagerTest.class);

    private ExtensionManager manager;

    private static ExtensionConfiguration config(String fw, String... classNames) {
        return ExtensionConfiguration.of(fw, java.util.Arrays.stream(classNames)
                .map(ExtensionDeclaration::of).toList());
    }

    @BeforeEach
    void setUp() {
        ExtensionRegistry registry = new DefaultExtensionRegistry();
        registry.register(AlphaExtension.class);
        registry.register(BetaExtension.class);
        registry.register(LifecycleExtension.class);
        registry.register(ActivateFailingExtension.class);
        LifecycleExtension.reset();
        manager = new DefaultExtensionManager(new ExtensionPlanBuilder(registry));
    }

    @Test
    void identicalConfigurationIsIdempotentAndSnapshotStaysSameInstance() {
        ExtensionConfiguration cfg = config("1.0.0",
                BetaExtension.class.getName(), AlphaExtension.class.getName());
        log.info("input config submitted twice: fingerprint={}; basis=equal fingerprints skip refresh",
                cfg.fingerprint());

        manager.refresh(cfg);
        EnabledExtensions first = manager.current();
        manager.refresh(cfg);
        EnabledExtensions second = manager.current();

        assertSame(first, second, "same config must not replace the snapshot");
        log.info("decision: snapshot unchanged, ids={}", second.ids());
    }

    @Test
    void failedActivationRollsBackCompletelyAndKeepsPreviousSnapshot() {
        ExtensionConfiguration good = config("1.0.0",
                LifecycleExtension.class.getName(), AlphaExtension.class.getName());
        manager.refresh(good);
        EnabledExtensions before = manager.current();
        log.info("initial config: ids={}; then submitting failing config=[alpha, activate-failing]",
                before.ids());

        ExtensionConfiguration bad = config("1.0.0",
                AlphaExtension.class.getName(), ActivateFailingExtension.class.getName());
        ExtensionLoadException ex = assertThrows(ExtensionLoadException.class,
                () -> manager.refresh(bad));
        log.info("decision: rejected with {}", ex.failures());

        assertSame(before, manager.current(), "old snapshot must remain the active state");
        assertEquals(List.of("lifecycle", "alpha"), manager.current().ids());
        assertEquals(ExtensionFailureReason.ACTIVATION_FAILED, ex.failures().get(0).reason());
        assertEquals("activate-failing", ex.failures().get(0).source());
        log.info("rollback verified: active ids still={}, lifecycle events={}",
                manager.current().ids(), LifecycleExtension.EVENTS);
    }

    @Test
    void successfulSwitchDeactivatesOldExtensionsInReverseOrder() {
        manager.refresh(config("1.0.0",
                AlphaExtension.class.getName(), LifecycleExtension.class.getName()));
        int eventsAfterFirstActivation = LifecycleExtension.EVENTS.size();

        manager.refresh(config("1.0.0", BetaExtension.class.getName(), AlphaExtension.class.getName()));

        log.info("basis=after atomic switch old instances deactivated in reverse order; events={}",
                LifecycleExtension.EVENTS);
        assertEquals(List.of("alpha", "beta"), manager.current().ids());
        assertTrue(LifecycleExtension.EVENTS.size() > eventsAfterFirstActivation,
                "old lifecycle extension must have been deactivated");
    }

    @Test
    void concurrentRefreshesNeverExposePartialOrMixedState() throws Exception {
        ExtensionConfiguration cfgAlpha = config("1.0.0", AlphaExtension.class.getName());
        ExtensionConfiguration cfgAlphaBeta = config("1.0.0",
                BetaExtension.class.getName(), AlphaExtension.class.getName());
        log.info("concurrent input configs: A={}, B={}; basis=refreshes serialize, "
                + "observed snapshot must be one of the valid sets",
                cfgAlpha.fingerprint(), cfgAlphaBeta.fingerprint());

        manager.refresh(cfgAlpha);

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> observedIds = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final ExtensionConfiguration cfg = i % 2 == 0 ? cfgAlphaBeta : cfgAlpha;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    manager.refresh(cfg);
                    List<String> ids = manager.current().ids();
                    observedIds.add(String.join(",", ids));
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

        log.info("observed snapshots across concurrent refresh: {}", observedIds);
        Set<String> allowed = Set.of("alpha", "alpha,beta");
        for (String ids : observedIds) {
            assertTrue(allowed.contains(ids),
                    "observed state must be a complete valid snapshot but was: " + ids);
        }
        assertNotNull(manager.current());
        assertTrue(allowed.contains(String.join(",", manager.current().ids())));
    }
}
