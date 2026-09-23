package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

/**
 * 扩展加载被拒绝时抛出的异常，携带可区分的原因与冲突对象。
 */
public class ExtensionLoadingException extends RuntimeException {

    private final LoadFailureReason reason;
    private final String conflictTarget;

    public ExtensionLoadingException(LoadFailureReason reason, String message, String conflictTarget) {
        super(message);
        this.reason = reason;
        this.conflictTarget = conflictTarget;
    }

    public LoadFailureReason reason() {
        return reason;
    }

    public String conflictTarget() {
        return conflictTarget;
    }
}
