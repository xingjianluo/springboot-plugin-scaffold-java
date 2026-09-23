package com.github.xingjianluo.springbootpluginscaffoldjava.tests;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionDeclaration;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionLoader;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionLoadingException;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionRegistry;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionSnapshot;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.LoadFailureReason;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.Version;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ExtensionRegistry} 单元测试：原子性、幂等性、回退与并发刷新。
 */
class ExtensionRegistryTests {

    private static final Logger log = LoggerFactory.getLogger(ExtensionRegistryTests.class);
    private static final String PKG =
            "com.github.xingjianluo.springbootpluginscaffoldjava.tests.ext.";

    private ExtensionRegistry newRegistry() {
        return new ExtensionRegistry(new ExtensionLoader(Version.parse("1.0.0")));
    }

    private ExtensionDeclaration decl(String name, String implClass, int priority, List<String> requires) {
        ExtensionDeclaration d = new ExtensionDeclaration();
        d.setName(name);
        d.setImplementation(PKG + implClass);
        d.setVersion("1.0.0");
        d.setPriority(priority);
        d.setRequires(requires == null ? new ArrayList<>() : requires);
        return d;
    }

    private List<ExtensionDeclaration> validAlpha() {
        return new ArrayList<>(List.of(decl("alpha", "AlphaExtension", 10, null)));
    }

    private List<ExtensionDeclaration> validAlphaBeta() {
        return new ArrayList<>(List.of(
                decl("alpha", "AlphaExtension", 10, null),
                decl("beta", "BetaExtension", 20, List.of("alpha:1.0.0"))));
    }

    private List<ExtensionDeclaration> invalidMissingDependency() {
        return new ArrayList<>(List.of(
                decl("beta", "BetaExtension", 20, List.of("alpha:1.0.0"))));
    }

    @Test
    @DisplayName("初始状态为空快照")
    void startsEmpty() {
        ExtensionRegistry registry = newRegistry();
        log.info("用例[初始为空] 判定依据=未刷新前 current() 返回不可变空集合");
        assertTrue(registry.listExtensions().isEmpty());
    }

    @Test
    @DisplayName("相同配置重复刷新必须幂等：changed=false 且快照引用不变")
    void refreshIsIdempotent() {
        ExtensionRegistry registry = newRegistry();
        ExtensionRegistry.RefreshResult first = registry.refresh(validAlphaBeta());
        ExtensionRegistry.RefreshResult second = registry.refresh(validAlphaBeta());
        log.info("用例[刷新幂等] 输入=alpha,beta 连续两次；first.changed={}, second.changed={}，判定依据=配置签名一致",
                first.changed(), second.changed());
        assertTrue(first.changed());
        assertFalse(second.changed());
        assertSame(first.snapshot(), second.snapshot());
        List<ExtensionDeclaration> shuffled = new ArrayList<>();
        shuffled.add(validAlphaBeta().get(1));
        shuffled.add(validAlphaBeta().get(0));
        ExtensionRegistry.RefreshResult third = registry.refresh(shuffled);
        assertFalse(third.changed(), "书写顺序不同、内容相同的配置必须幂等");
    }

    @Test
    @DisplayName("刷新失败必须整体回退：旧扩展集合与快照引用保持不变，无部分生效")
    void failedRefreshRollsBack() {
        ExtensionRegistry registry = newRegistry();
        ExtensionRegistry.RefreshResult first = registry.refresh(validAlphaBeta());
        ExtensionSnapshot before = registry.current();

        log.info("用例[失败回退] 先加载 alpha,beta，再提交缺失依赖配置；判定依据=异常后 current() 必须仍是旧快照");
        ExtensionLoadingException ex = assertThrows(ExtensionLoadingException.class,
                () -> registry.refresh(invalidMissingDependency()));
        assertEquals(LoadFailureReason.MISSING_DEPENDENCY, ex.reason());
        log.info("刷新被拒绝原因={}, 冲突对象={}", ex.reason(), ex.conflictTarget());

        assertSame(before, registry.current(), "失败后必须仍是旧快照引用");
        assertEquals(List.of("alpha", "beta"),
                registry.listExtensions().stream().map(e -> e.name()).toList());
    }

    @Test
    @DisplayName("刷新期间并发读取只能观察到完整旧快照或完整新快照，且重复提交不产生额外切换")
    void concurrentRefreshAndRead() throws Exception {
        ExtensionRegistry registry = newRegistry();
        registry.refresh(validAlpha());

        int readerThreads = 6;
        int refreshRounds = 50;
        ExecutorService pool = Executors.newFixedThreadPool(readerThreads + 2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        ConcurrentLinkedQueue<String> observedSignatures = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new ArrayList<>();

        // 读取线程：只允许观察到“1 个扩展的旧快照”或“2 个扩展的新快照”，且顺序稳定
        for (int t = 0; t < readerThreads; t++) {
            futures.add(pool.submit(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        ExtensionSnapshot snap = registry.current();
                        observedSignatures.add(snap.signature());
                        int n = snap.extensions().size();
                        if (n != 1 && n != 2) {
                            failures.incrementAndGet();
                            return;
                        }
                        List<String> names = snap.extensions().stream().map(e -> e.name()).toList();
                        List<String> sorted = names.stream().sorted().toList();
                        if (!new HashSet<>(names).equals(new HashSet<>(sorted)) || names.size() != sorted.size()) {
                            failures.incrementAndGet();
                            return;
                        }
                        if (n == 2 && !names.equals(List.of("alpha", "beta"))) {
                            failures.incrementAndGet();
                        }
                    }
                } catch (RuntimeException ignored) {
                    // 线程池关闭时退出
                }
            }));
        }

        // 刷新线程 A：在 alpha 与 alpha,beta 间来回切换
        Future<?> refresherA = pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < refreshRounds; i++) {
                    registry.refresh(i % 2 == 0 ? validAlphaBeta() : validAlpha());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        // 刷新线程 B：重复提交相同的 alpha,beta 配置，验证并发幂等
        Future<?> refresherB = pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < refreshRounds; i++) {
                    ExtensionRegistry.RefreshResult r = registry.refresh(validAlphaBeta());
                    if (r.changed() && !registry.current().equals(r.snapshot())) {
                        failures.incrementAndGet();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        start.countDown();
        refresherA.get(30, TimeUnit.SECONDS);
        refresherB.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        Set<String> distinctSignatures = new HashSet<>(observedSignatures);
        log.info("用例[并发刷新] 读取观察次数={}，观察到的不同快照签名数={}（期望<=2），失败计数={}，判定依据=volatile 快照引用原子替换",
                observedSignatures.size(), distinctSignatures.size(), failures.get());
        assertTrue(distinctSignatures.size() <= 2, "读者只能看到旧/新两种完整快照，实际签名=" + distinctSignatures);
        assertEquals(0, failures.get(), "并发期间不得观察到半加载或新旧混用状态");

        // 并发结束后再次提交配置，结果确定、可复现
        ExtensionRegistry.RefreshResult finalResult = registry.refresh(validAlphaBeta());
        assertEquals(List.of("alpha", "beta"),
                finalResult.snapshot().extensions().stream().map(e -> e.name()).toList());
    }

    @Test
    @DisplayName("并发提交相同配置只有一次实际切换")
    void concurrentSameConfigSwitchesOnce() throws Exception {
        ExtensionRegistry registry = newRegistry();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger changedCount = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    if (registry.refresh(validAlphaBeta()).changed()) {
                        changedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
        log.info("用例[并发相同配置] {} 个线程同时提交 alpha,beta，changed 次数={}（期望=1），判定依据=锁内双检签名",
                threads, changedCount.get());
        assertEquals(1, changedCount.get());
    }
}
