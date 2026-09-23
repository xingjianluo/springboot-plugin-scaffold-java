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
}
