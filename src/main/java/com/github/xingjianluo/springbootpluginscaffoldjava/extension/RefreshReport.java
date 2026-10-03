package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

import java.util.List;

/**
 * 一次刷新/回滚的可解释结果：输入指纹、是否真正生效、逐扩展的处理结论。
 *
 * @param applied           是否真正发生了切换；false 表示幂等跳过（提交配置与当前指纹一致）
 * @param rolledBack        是否为“回到上一次成功配置”的回滚操作
 * @param previousFingerprint 切换前生效配置的指纹
 * @param currentFingerprint  切换后生效配置的指纹（幂等跳过时与 previous 相同）
 * @param changes           逐扩展处理结论
 */
public record RefreshReport(boolean applied, boolean rolledBack,
                            String previousFingerprint, String currentFingerprint,
                            List<ExtensionChange> changes) {

    public RefreshReport {
        changes = List.copyOf(changes);
    }
}
