package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

/**
 * 简化语义化版本比较：按 . 分段，逐段按数字比较，缺失段视为 0。
 * 例如 1.2 与 1.2.0 相等；2.0.1 大于 2.0。
 */
public final class Version {

    private Version() {
    }

    /**
     * 比较两个版本号。
     *
     * @return 负数表示 a 小于 b，0 相等，正数表示 a 大于 b
     */
    public static int compare(String a, String b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("version must not be null");
        }
        String[] partsA = a.trim().split("\\.");
        String[] partsB = b.trim().split("\\.");
        int len = Math.max(partsA.length, partsB.length);
        for (int i = 0; i < len; i++) {
            int na = i < partsA.length ? parseSegment(partsA[i], a) : 0;
            int nb = i < partsB.length ? parseSegment(partsB[i], b) : 0;
            if (na != nb) {
                return Integer.compare(na, nb);
            }
        }
        return 0;
    }

    /** 判断实际版本 actual 是否满足 requiredMinimum（actual &gt;= requiredMinimum）。 */
    public static boolean satisfies(String requiredMinimum, String actual) {
        return compare(actual, requiredMinimum) >= 0;
    }

    private static int parseSegment(String segment, String fullVersion) {
        String s = segment.trim();
        if (s.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseUnsignedInt(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid version: " + fullVersion, e);
        }
    }
}
