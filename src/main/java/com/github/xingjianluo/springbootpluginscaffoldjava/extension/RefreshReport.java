package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

import java.util.List;
import java.util.Objects;

/**
 * 一次刷新或回退的处理报告：说清楚每个扩展分别被怎么处理、为什么。
 *
 * @param mode       "REFRESH"（提交新配置）或 "RESTORE"（回退到上一次成功配置）
 * @param fingerprint 本次目标配置的指纹
 * @param skipped    是否因幂等而整次跳过（同一份配置重复提交）
 * @param decisions  逐扩展的处理结论（按新集合激活顺序排列，移除项排在末尾）
 * @param snapshotIds 本次生效后的快照 id 顺序
 */
public record RefreshReport(String mode, String fingerprint, boolean skipped,
                            List<ExtensionDecision> decisions, List<String> snapshotIds) {

    public static final String MODE_REFRESH = "REFRESH";
    public static final String MODE_RESTORE = "RESTORE";

    public RefreshReport {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(fingerprint, "fingerprint");
        decisions = List.copyOf(decisions);
        snapshotIds = List.copyOf(snapshotIds);
    }
}
