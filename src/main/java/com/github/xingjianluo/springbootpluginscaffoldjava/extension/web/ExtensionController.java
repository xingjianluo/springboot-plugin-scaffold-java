package com.github.xingjianluo.springbootpluginscaffoldjava.extension.web;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionDeclaration;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionLoadingException;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionRegistry;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionSnapshot;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.LoadedExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 扩展集合的对外查询与运行期刷新接口。
 *
 * <ul>
 *   <li>{@code GET /api/extensions}：查询当前生效集合（稳定顺序）；</li>
 *   <li>{@code GET /api/extensions/health}：查询当前快照签名；</li>
 *   <li>{@code POST /api/extensions/refresh}：提交新配置进行原子刷新，
 *       相同配置幂等（{@code changed=false}），失败整体回退并返回 422 与可区分原因。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/extensions")
public class ExtensionController {

    private static final Logger log = LoggerFactory.getLogger(ExtensionController.class);

    private final ExtensionRegistry registry;

    public ExtensionController(ExtensionRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public ExtensionView list() {
        ExtensionSnapshot snapshot = registry.current();
        return ExtensionView.from(snapshot);
    }

    @GetMapping("/health")
    public HealthView health() {
        ExtensionSnapshot snapshot = registry.current();
        return new HealthView(snapshot.signature(), snapshot.extensions().size());
    }

    @PostMapping("/refresh")
    public RefreshResponseView refresh(@RequestBody RefreshRequestView request) {
        List<ExtensionDeclaration> declarations =
                request == null ? List.of() : request.extensions();
        log.info("HTTP 刷新请求，扩展声明={}", declarations);
        ExtensionRegistry.RefreshResult result = registry.refresh(declarations);
        return new RefreshResponseView(result.changed(), result.snapshot().signature(),
                ExtensionView.from(result.snapshot()));
    }

    @ExceptionHandler(ExtensionLoadingException.class)
    public ResponseEntity<ErrorView> handleLoadingFailure(ExtensionLoadingException ex) {
        log.warn("扩展刷新被拒绝: reason={}, conflictTarget={}, message={}",
                ex.reason(), ex.conflictTarget(), ex.getMessage());
        return ResponseEntity.unprocessableEntity().body(
                new ErrorView(ex.reason().name(), ex.getMessage(), ex.conflictTarget()));
    }

    /** 扩展对外视图。 */
    public record ExtensionView(String signature, List<ExtensionItemView> extensions) {

        static ExtensionView from(ExtensionSnapshot snapshot) {
            return new ExtensionView(snapshot.signature(),
                    snapshot.extensions().stream().map(ExtensionItemView::from).toList());
        }
    }

    /** 单个扩展对外视图（不暴露实例）。 */
    public record ExtensionItemView(String name, String version, int priority, String type) {

        static ExtensionItemView from(LoadedExtension e) {
            return new ExtensionItemView(e.name(), e.version(), e.priority(),
                    e.instance() == null ? null : e.instance().getClass().getName());
        }
    }

    public record RefreshRequestView(List<ExtensionDeclaration> extensions) {
    }

    public record RefreshResponseView(boolean changed, String signature, ExtensionView extensions) {
    }

    public record HealthView(String signature, int count) {
    }

    public record ErrorView(String reason, String message, String conflictTarget) {
    }
}
