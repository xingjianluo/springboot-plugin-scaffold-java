package com.github.xingjianluo.springbootpluginscaffoldjava.web;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionFailure;
import com.github.xingjianluo.springbootpluginscaffoldjava.extension.ExtensionLoadException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * 把扩展加载失败翻译为稳定的 HTTP 响应：
 * 422 + 按原因枚举可区分的失败明细；非法入参返回 400。
 */
@RestControllerAdvice
public class ExtensionExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ExtensionExceptionHandler.class);

    @ExceptionHandler(ExtensionLoadException.class)
    public ResponseEntity<ExtensionViews.RefreshErrorView> handle(ExtensionLoadException ex) {
        List<ExtensionViews.FailureView> failures = ex.failures().stream()
                .map(ExtensionExceptionHandler::toView)
                .toList();
        log.warn("extension load rejected: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ExtensionViews.RefreshErrorView("EXTENSION_LOAD_REJECTED", failures));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ExtensionViews.RefreshErrorView> handleBadRequest(IllegalArgumentException ex) {
        ExtensionViews.FailureView failure =
                new ExtensionViews.FailureView("INVALID_REQUEST", null, null, ex.getMessage());
        return ResponseEntity.badRequest()
                .body(new ExtensionViews.RefreshErrorView("INVALID_REQUEST", List.of(failure)));
    }

    /** 当前状态下操作不允许（例如没有可回到的上一次成功配置）。 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ExtensionViews.RefreshErrorView> handleConflict(IllegalStateException ex) {
        ExtensionViews.FailureView failure =
                new ExtensionViews.FailureView("ILLEGAL_STATE", null, null, ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ExtensionViews.RefreshErrorView("ILLEGAL_STATE", List.of(failure)));
    }

    private static ExtensionViews.FailureView toView(ExtensionFailure failure) {
        return new ExtensionViews.FailureView(
                failure.reason().name(), failure.source(), failure.conflict(), failure.detail());
    }
}
