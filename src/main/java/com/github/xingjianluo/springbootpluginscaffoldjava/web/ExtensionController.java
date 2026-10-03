package com.github.xingjianluo.springbootpluginscaffoldjava.web;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.EnabledExtensions;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.Extension;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionConfiguration;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionDeclaration;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionManager;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.RefreshReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 查询当前生效扩展、提交刷新配置。
 * 查询直接读取不可变快照，刷新期间只会看到完整的旧集合或完整的新集合。
 */
@RestController
@RequestMapping("/api/extensions")
public class ExtensionController {

    private static final Logger log = LoggerFactory.getLogger(ExtensionController.class);

    private final ExtensionManager extensionManager;

    public ExtensionController(ExtensionManager extensionManager) {
        this.extensionManager = extensionManager;
    }

    @GetMapping
    public ExtensionViews.SnapshotView current() {
        return toView(extensionManager.current());
    }

    @PostMapping("/refresh")
    public ExtensionViews.RefreshResponseView refresh(@RequestBody(required = false) ExtensionViews.RefreshRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body must not be null");
        }
        String frameworkVersion = request.frameworkVersion() == null
                ? extensionManager.current().configuration().frameworkVersion()
                : request.frameworkVersion().trim();
        List<ExtensionDeclaration> declarations = parseDeclarations(request.enabled());
        ExtensionConfiguration configuration = ExtensionConfiguration.of(frameworkVersion, declarations);
        log.info("refresh api called: frameworkVersion={}, enabled={}", frameworkVersion,
                declarations.stream().map(ExtensionDeclaration::className).toList());
        RefreshReport report = extensionManager.refresh(configuration);
        return toResponse(extensionManager.current(), report);
    }

    /**
     * 一步回到上一次成功生效的配置：复用当时快照的同一批实例，不照配置重建。
     * 没有可回退快照时返回 409。
     */
    @PostMapping("/restore")
    public ExtensionViews.RefreshResponseView restore() {
        log.info("restore api called: currentIds={}", extensionManager.current().ids());
        RefreshReport report = extensionManager.restore();
        return toResponse(extensionManager.current(), report);
    }

    private static List<ExtensionDeclaration> parseDeclarations(List<String> enabled) {
        List<ExtensionDeclaration> declarations = new ArrayList<>();
        if (enabled == null) {
            return declarations;
        }
        for (String item : enabled) {
            if (item == null) {
                continue;
            }
            // 同时兼容 "a,b,c" 逗号分隔串与独立列表项两种提交形式
            for (String className : item.split(",")) {
                String trimmed = className.trim();
                if (!trimmed.isEmpty()) {
                    declarations.add(ExtensionDeclaration.of(trimmed));
                }
            }
        }
        return declarations;
    }

    private static ExtensionViews.SnapshotView toView(EnabledExtensions snapshot) {
        List<ExtensionViews.ExtensionView> views = snapshot.extensions().stream()
                .map(ExtensionController::toExtensionView)
                .toList();
        return new ExtensionViews.SnapshotView(snapshot.configuration().frameworkVersion(), views);
    }

    private static ExtensionViews.RefreshResponseView toResponse(EnabledExtensions snapshot,
                                                                 RefreshReport report) {
        List<ExtensionViews.DecisionView> decisions = report.decisions().stream()
                .map(d -> new ExtensionViews.DecisionView(d.id(), d.action().name(), d.reason()))
                .toList();
        ExtensionViews.RefreshReportView reportView = new ExtensionViews.RefreshReportView(
                report.mode(), report.fingerprint(), report.skipped(),
                decisions, report.snapshotIds());
        return new ExtensionViews.RefreshResponseView(
                snapshot.configuration().frameworkVersion(),
                snapshot.extensions().stream().map(ExtensionController::toExtensionView).toList(),
                reportView);
    }

    private static ExtensionViews.ExtensionView toExtensionView(Extension extension) {
        return new ExtensionViews.ExtensionView(
                extension.id(),
                extension.version(),
                extension.priority(),
                List.copyOf(extension.requiredExtensions()),
                extension.requiredFrameworkVersion(),
                extension.getClass().getName());
    }
}
