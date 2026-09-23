package com.github.xingjianluo.springbootpluginscaffoldjava.tests;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionDeclaration;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionLoader;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionLoadingException;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionSnapshot;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.LoadFailureReason;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.Version;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ExtensionLoader} 单元测试。每个用例都打印输入配置、期望结果与判定依据。
 */
class ExtensionLoaderTests {

    private static final Logger log = LoggerFactory.getLogger(ExtensionLoaderTests.class);

    private static final String PKG =
            "com.github.xingjianluo.springbootpluginscaffoldjava.tests.ext.";

    private ExtensionLoader loader(String frameworkVersion) {
        return new ExtensionLoader(Version.parse(frameworkVersion));
    }

    private ExtensionDeclaration decl(String name, String implClass, String version,
                                      int priority, List<String> requires, String minFramework) {
        ExtensionDeclaration d = new ExtensionDeclaration();
        d.setName(name);
        d.setImplementation(implClass == null ? null : PKG + implClass);
        d.setVersion(version);
        d.setPriority(priority);
        d.setRequires(requires == null ? new ArrayList<>() : requires);
        d.setMinFrameworkVersion(minFramework);
        return d;
    }

    private void assertRejected(String caseName, List<ExtensionDeclaration> input,
                                LoadFailureReason expected, String frameworkVersion) {
        log.info("用例[{}]输入配置={}，期望拒绝原因={}，判定依据=加载器应在对应校验阶段中止",
                caseName, input, expected);
        ExtensionLoadingException ex = assertThrows(ExtensionLoadingException.class,
                () -> loader(frameworkVersion).load(input));
        log.info("用例[{}]实际拒绝原因={}, 冲突对象={}, 说明={}",
                caseName, ex.reason(), ex.conflictTarget(), ex.getMessage());
        assertEquals(expected, ex.reason(), "拒绝原因必须可区分: " + caseName);
    }

    @Test
    @DisplayName("重复声明必须被拒绝，原因为 DUPLICATE_DECLARATION")
    void duplicateDeclarationRejected() {
        List<ExtensionDeclaration> input = List.of(
                decl("alpha", "AlphaExtension", "1.0.0", 10, null, null),
                decl("alpha", "LegacyExtension", "1.0.0", 20, null, null));
        assertRejected("重复声明", input, LoadFailureReason.DUPLICATE_DECLARATION, "1.0.0");
    }

    @Test
    @DisplayName("实现类不存在必须被拒绝，原因为 UNDECLARED_IMPLEMENTATION")
    void missingClassRejected() {
        List<ExtensionDeclaration> input = List.of(
                decl("ghost", "DoesNotExistExtension", "1.0.0", 10, null, null));
        assertRejected("实现类不存在", input, LoadFailureReason.UNDECLARED_IMPLEMENTATION, "1.0.0");
    }

    @Test
    @DisplayName("实现类未实现扩展接口必须被拒绝，原因为 UNDECLARED_IMPLEMENTATION")
    void wrongTypeRejected() {
        List<ExtensionDeclaration> input = List.of(
                decl("plain", "PlainObject", "1.0.0", 10, null, null));
        assertRejected("未实现接口", input, LoadFailureReason.UNDECLARED_IMPLEMENTATION, "1.0.0");
    }

    @Test
    @DisplayName("抽象类/构造抛异常/无公开构造必须以 UNINSTANTIABLE_IMPLEMENTATION 拒绝")
    void uninstantiableRejected() {
        assertRejected("抽象类", List.of(decl("a", "AbstractExtension", "1.0.0", 1, null, null)),
                LoadFailureReason.UNINSTANTIABLE_IMPLEMENTATION, "1.0.0");
        assertRejected("构造抛异常", List.of(decl("t", "ThrowingExtension", "1.0.0", 1, null, null)),
                LoadFailureReason.UNINSTANTIABLE_IMPLEMENTATION, "1.0.0");
        assertRejected("私有构造", List.of(decl("p", "PrivateConstructorExtension", "1.0.0", 1, null, null)),
                LoadFailureReason.UNINSTANTIABLE_IMPLEMENTATION, "1.0.0");
    }

    @Test
    @DisplayName("依赖缺失必须被拒绝并指出冲突对象，原因为 MISSING_DEPENDENCY")
    void missingDependencyRejected() {
        List<ExtensionDeclaration> input = List.of(
                decl("beta", "BetaExtension", "2.0.0", 10, List.of("alpha:1.0.0"), null));
        ExtensionLoadingException ex = assertThrows(ExtensionLoadingException.class,
                () -> loader("1.0.0").load(input));
        log.info("用例[依赖缺失]输入={}，实际原因={}，冲突对象={}，依据=被依赖者未启用",
                input, ex.reason(), ex.conflictTarget());
        assertEquals(LoadFailureReason.MISSING_DEPENDENCY, ex.reason());
        assertTrue(ex.conflictTarget().contains("beta") && ex.conflictTarget().contains("alpha"));
    }

    @Test
    @DisplayName("依赖最低版本不满足必须被拒绝，原因为 DEPENDENCY_VERSION_NOT_SATISFIED")
    void dependencyVersionRejected() {
        List<ExtensionDeclaration> input = List.of(
                decl("alpha", "AlphaExtension", "1.2.0", 10, null, null),
                decl("beta", "BetaExtension", "2.0.0", 20, List.of("alpha:1.5.0"), null));
        assertRejected("依赖版本过低", input,
                LoadFailureReason.DEPENDENCY_VERSION_NOT_SATISFIED, "1.0.0");
    }

    @Test
    @DisplayName("循环依赖必须被拒绝并指出环路，原因为 CIRCULAR_DEPENDENCY")
    void circularDependencyRejected() {
        List<ExtensionDeclaration> input = List.of(
                decl("alpha", "AlphaExtension", "1.0.0", 10, List.of("gamma:1.0.0"), null),
                decl("beta", "BetaExtension", "1.0.0", 20, List.of("alpha:1.0.0"), null),
                decl("gamma", "GammaExtension", "1.0.0", 30, List.of("beta:1.0.0"), null));
        ExtensionLoadingException ex = assertThrows(ExtensionLoadingException.class,
                () -> loader("1.0.0").load(input));
        log.info("用例[循环依赖]输入={}，实际原因={}，冲突环路={}，依据=拓扑排序无法穷尽节点",
                input, ex.reason(), ex.conflictTarget());
        assertEquals(LoadFailureReason.CIRCULAR_DEPENDENCY, ex.reason());
        assertTrue(ex.conflictTarget().contains("alpha"));
    }

    @Test
    @DisplayName("框架最低版本不满足必须被拒绝，原因为 FRAMEWORK_VERSION_NOT_SATISFIED")
    void frameworkVersionRejected() {
        List<ExtensionDeclaration> input = List.of(
                decl("alpha", "AlphaExtension", "1.0.0", 10, null, "2.0.0"));
        assertRejected("框架版本过低", input,
                LoadFailureReason.FRAMEWORK_VERSION_NOT_SATISFIED, "1.0.0");
    }

    @Test
    @DisplayName("加载成功：被依赖者在前，其余按 priority/name 排序，结果稳定可复现")
    void stableOrderingAndReproducible() {
        List<ExtensionDeclaration> inputA = List.of(
                decl("gamma", "GammaExtension", "1.0.0", 30, List.of("beta:2.0.0"), null),
                decl("legacy", "LegacyExtension", "1.0.0", 5, null, null),
                decl("beta", "BetaExtension", "2.0.0", 20, List.of("alpha:1.0.0"), null),
                decl("alpha", "AlphaExtension", "1.2.0", 10, null, null));
        // 打乱书写顺序再次加载
        List<ExtensionDeclaration> inputB = List.of(inputA.get(3), inputA.get(1),
                inputA.get(0), inputA.get(2));

        ExtensionSnapshot s1 = loader("1.0.0").load(inputA);
        ExtensionSnapshot s2 = loader("1.0.0").load(inputB);

        List<String> names = s1.extensions().stream().map(e -> e.name()).toList();
        log.info("用例[稳定排序]加载顺序={}，判定依据=每轮在可输出节点中取 priority 最小：legacy(5) 先出，依赖链内 alpha 先于 beta 先于 gamma", names);
        assertEquals(List.of("legacy", "alpha", "beta", "gamma"), names);
        assertEquals(s1.signature(), s2.signature(), "书写顺序不同但配置相同，签名必须一致");
        List<String> names2 = s2.extensions().stream().map(e -> e.name()).toList();
        assertEquals(names, names2, "加载集合与顺序必须可复现");
    }

    @Test
    @DisplayName("整体拒绝：任一扩展失败时不产生部分结果")
    void allOrNothing() {
        List<ExtensionDeclaration> input = List.of(
                decl("alpha", "AlphaExtension", "1.0.0", 10, null, null),
                decl("broken", "ThrowingExtension", "1.0.0", 20, null, null));
        assertRejected("整体拒绝", input, LoadFailureReason.UNINSTANTIABLE_IMPLEMENTATION, "1.0.0");
    }
}
