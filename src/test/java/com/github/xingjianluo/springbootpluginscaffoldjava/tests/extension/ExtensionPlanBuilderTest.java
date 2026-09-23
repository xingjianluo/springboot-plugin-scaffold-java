package com.github.xingjianluo.springbootpluginscaffoldjava.tests.extension;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.DefaultExtensionRegistry;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.EnabledExtensions;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionConfiguration;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionDeclaration;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionFailureReason;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionLoadException;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionPlanBuilder;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionRegistry;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.AlphaExtension;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.BetaExtension;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.CycleOneExtension;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.CycleTwoExtension;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.GammaHighVersionExtension;
import com.github.xingjianluo.springbootpluginscaffoldjava.tests.fixtures.NoNoArgConstructorExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 每个用例都打印输入配置与判定依据；加载成功结果必须跨重复构建保持一致。
 */
class ExtensionPlanBuilderTest {

    private static final Logger log = LoggerFactory.getLogger(ExtensionPlanBuilderTest.class);

    private ExtensionRegistry registry;
    private ExtensionPlanBuilder builder;

    @BeforeEach
    void setUp() {
        registry = new DefaultExtensionRegistry();
        registry.register(AlphaExtension.class);
        registry.register(BetaExtension.class);
        registry.register(GammaHighVersionExtension.class);
        registry.register(CycleOneExtension.class);
        registry.register(CycleTwoExtension.class);
        registry.register(NoNoArgConstructorExtension.class);
        builder = new ExtensionPlanBuilder(registry);
    }

    private static ExtensionConfiguration config(String fw, String... classNames) {
        List<ExtensionDeclaration> declarations = java.util.Arrays.stream(classNames)
                .map(ExtensionDeclaration::of).toList();
        return ExtensionConfiguration.of(fw, declarations);
    }

    private ExtensionLoadException expectRejected(ExtensionConfiguration configuration) {
        return assertThrows(ExtensionLoadException.class, () -> builder.build(configuration));
    }

    @Test
    void loadsInStableDependencyThenPriorityOrderAndIsReproducible() {
        ExtensionConfiguration cfg = config("1.0.0",
                BetaExtension.class.getName(), AlphaExtension.class.getName());
        log.info("input config: fw={}, declared=[beta, alpha]; "
                + "basis=topological(dependency first), tie-break=(priority,declarationIndex)", cfg.frameworkVersion());

        EnabledExtensions first = builder.build(cfg);
        EnabledExtensions second = builder.build(cfg);

        log.info("loaded order={} (rebuilt order={})", first.ids(), second.ids());
        assertEquals(List.of("alpha", "beta"), first.ids());
        assertEquals(first.ids(), second.ids(), "repeated builds must be reproducible");
    }

    @Test
    void rejectsUndeclaredImplementationWithDistinctReason() {
        ExtensionConfiguration cfg = config("1.0.0", "com.example.NotRegisteredExtension");
        log.info("input config: fw={}, declared=[com.example.NotRegisteredExtension]; "
                + "basis=class name absent from registry", cfg.frameworkVersion());

        ExtensionLoadException ex = expectRejected(cfg);
        log.info("decision: {}", ex.getMessage());
        assertEquals(ExtensionFailureReason.UNDECLARED_IMPLEMENTATION, ex.failures().get(0).reason());
    }

    @Test
    void rejectsImplementationThatCannotBeInstantiated() {
        ExtensionConfiguration cfg = config("1.0.0", NoNoArgConstructorExtension.class.getName());
        log.info("input config: fw={}, declared=[NoNoArgConstructorExtension]; "
                + "basis=no accessible no-arg constructor", cfg.frameworkVersion());

        ExtensionLoadException ex = expectRejected(cfg);
        log.info("decision: {}", ex.getMessage());
        assertEquals(ExtensionFailureReason.INSTANTIATION_FAILED, ex.failures().get(0).reason());
        assertEquals(NoNoArgConstructorExtension.class.getName(), ex.failures().get(0).source());
    }

    @Test
    void rejectsDuplicateDeclarationWithConflictingObject() {
        ExtensionConfiguration cfg = config("1.0.0",
                AlphaExtension.class.getName(), AlphaExtension.class.getName());
        log.info("input config: fw={}, declared=[alpha, alpha]; basis=same class name twice",
                cfg.frameworkVersion());

        ExtensionLoadException ex = expectRejected(cfg);
        log.info("decision: {}", ex.getMessage());
        assertEquals(ExtensionFailureReason.DUPLICATE_DECLARATION, ex.failures().get(0).reason());
        assertEquals(AlphaExtension.class.getName(), ex.failures().get(0).conflict());
    }

    @Test
    void rejectsMissingDependencyAndNamesTheMissingObject() {
        ExtensionConfiguration cfg = config("1.0.0", BetaExtension.class.getName());
        log.info("input config: fw={}, declared=[beta] without alpha; "
                + "basis=required dependency absent from enabled set", cfg.frameworkVersion());

        ExtensionLoadException ex = expectRejected(cfg);
        log.info("decision: {}", ex.getMessage());
        assertEquals(ExtensionFailureReason.MISSING_DEPENDENCY, ex.failures().get(0).reason());
        assertEquals("alpha", ex.failures().get(0).conflict());
        assertEquals("beta", ex.failures().get(0).source());
    }

    @Test
    void rejectsWhenMinimumFrameworkVersionNotSatisfied() {
        ExtensionConfiguration cfg = config("1.0.0", GammaHighVersionExtension.class.getName());
        log.info("input config: fw=1.0.0, declared=[gamma-high requires>=9.0.0]; "
                + "basis=actual framework version below required minimum", cfg.frameworkVersion());

        ExtensionLoadException ex = expectRejected(cfg);
        log.info("decision: {}", ex.getMessage());
        assertEquals(ExtensionFailureReason.INCOMPATIBLE_VERSION, ex.failures().get(0).reason());
        assertEquals("9.0.0", ex.failures().get(0).conflict());
    }

    @Test
    void rejectsCircularDependencyAndReportsAllCycleMembers() {
        ExtensionConfiguration cfg = config("1.0.0",
                CycleOneExtension.class.getName(), CycleTwoExtension.class.getName());
        log.info("input config: fw={}, declared=[cycle-one->cycle-two, cycle-two->cycle-one]; "
                + "basis=topological sort leaves nodes in cycle", cfg.frameworkVersion());

        ExtensionLoadException ex = expectRejected(cfg);
        log.info("decision: {}", ex.getMessage());
        assertEquals(2, ex.failures().size());
        assertTrue(ex.failures().stream()
                .allMatch(f -> f.reason() == ExtensionFailureReason.CIRCULAR_DEPENDENCY));
        List<String> sources = ex.failures().stream().map(f -> f.source()).sorted().toList();
        assertEquals(List.of("cycle-one", "cycle-two"), sources);
    }

    @Test
    void collectsAllFailuresWithDistinguishableReasonsInOneRejection() {
        ExtensionConfiguration cfg = config("1.0.0",
                BetaExtension.class.getName(),                 // 缺 alpha
                "com.example.NotRegisteredExtension",          // 未声明
                GammaHighVersionExtension.class.getName(),     // 版本不兼容
                GammaHighVersionExtension.class.getName());    // 重复声明
        log.info("input config: fw=1.0.0, declared=[beta(missing dep), unknown class, gamma-high, gamma-high]");

        ExtensionLoadException ex = expectRejected(cfg);
        ex.failures().forEach(f -> log.info("collected failure: {}", f.describe()));

        List<ExtensionFailureReason> reasons =
                ex.failures().stream().map(f -> f.reason()).toList();
        assertTrue(reasons.contains(ExtensionFailureReason.MISSING_DEPENDENCY));
        assertTrue(reasons.contains(ExtensionFailureReason.UNDECLARED_IMPLEMENTATION));
        assertTrue(reasons.contains(ExtensionFailureReason.INCOMPATIBLE_VERSION));
        assertTrue(reasons.contains(ExtensionFailureReason.DUPLICATE_DECLARATION));
    }
}
