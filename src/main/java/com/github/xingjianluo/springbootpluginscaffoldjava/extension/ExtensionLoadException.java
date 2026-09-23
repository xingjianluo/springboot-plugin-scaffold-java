package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 扩展加载/刷新被拒绝时抛出，携带全部失败原因，便于调用方按原因区分处理。
 */
public class ExtensionLoadException extends RuntimeException {

    private final transient List<ExtensionFailure> failures;

    public ExtensionLoadException(List<ExtensionFailure> failures) {
        super(failures.stream().map(ExtensionFailure::describe).collect(Collectors.joining("; ")));
        this.failures = List.copyOf(failures);
    }

    public List<ExtensionFailure> failures() {
        return failures;
    }
}
