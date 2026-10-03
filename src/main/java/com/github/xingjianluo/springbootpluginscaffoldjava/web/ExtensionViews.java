package com.github.xingjianluo.springbootpluginscaffoldjava.web;

import java.util.List;

/**
 * HTTP 接口使用的只读视图。
 */
public final class ExtensionViews {

    private ExtensionViews() {
    }

    public record ExtensionView(String id, String version, int priority,
                                List<String> requiredExtensions,
                                String requiredFrameworkVersion, String implementation) {
    }

    public record SnapshotView(String frameworkVersion, List<ExtensionView> extensions) {
    }

    public record RefreshRequest(String frameworkVersion, List<String> enabled) {
    }

    public record FailureView(String reason, String source, String conflict, String detail) {
    }

    public record RefreshErrorView(String error, List<FailureView> failures) {
    }

    /** 单个扩展的差异判定与处理结论视图。 */
    public record ChangeView(String id, String action, String reason, String detail) {
    }

    /** 一次刷新/回滚的结果视图：当前快照 + 逐扩展处理结论。 */
    public record RefreshResultView(String frameworkVersion, List<ExtensionView> extensions,
                                    boolean applied, boolean rolledBack,
                                    String previousFingerprint, String currentFingerprint,
                                    List<ChangeView> changes) {
    }
}
