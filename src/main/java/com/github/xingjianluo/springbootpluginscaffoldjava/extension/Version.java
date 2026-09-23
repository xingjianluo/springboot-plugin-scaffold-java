package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

import java.util.Objects;

/**
 * 简单的语义版本号（major.minor.patch），支持按数值比较，缺省段按 0 处理。
 * 非数字的限定后缀（如 {@code -SNAPSHOT}）在解析时被忽略，三段均缺省的非法输入按 0.0.0 处理。
 */
public record Version(int major, int minor, int patch) implements Comparable<Version> {

    public Version {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("版本号段不能为负数: " + major + "." + minor + "." + patch);
        }
    }

    /**
     * 解析形如 {@code 1}、{@code 1.2}、{@code 1.2.3}、{@code 1.2.3-SNAPSHOT} 的版本字符串。
     * 空白段按 0 处理；null 或空白输入返回 {@code 0.0.0}。
     */
    public static Version parse(String text) {
        if (text == null || text.isBlank()) {
            return new Version(0, 0, 0);
        }
        // 去掉构建/预发布后缀
        String core = text.trim().split("[-+]", 2)[0];
        String[] parts = core.split("\\.", -1);
        int major = parsePart(parts, 0);
        int minor = parsePart(parts, 1);
        int patch = parsePart(parts, 2);
        return new Version(major, minor, patch);
    }

    private static int parsePart(String[] parts, int index) {
        if (index >= parts.length || parts[index].isBlank()) {
            return 0;
        }
        String digits = parts[index].replaceAll("\\D.*$", "");
        return digits.isBlank() ? 0 : Integer.parseInt(digits);
    }

    /** 当前版本是否不低于 required。 */
    public boolean satisfies(Version required) {
        return compareTo(required) >= 0;
    }

    @Override
    public int compareTo(Version other) {
        Objects.requireNonNull(other, "other");
        int cmp = Integer.compare(major, other.major);
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(minor, other.minor);
        if (cmp != 0) {
            return cmp;
        }
        return Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
