// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.Objects;

/**
 * 验证过的版本范围：下界含、上界不含，写成 [3.25.69, 3.26)。实测过的模组版本才写进来；
 * 装的版本不在范围内就不登记这个模组的联动，换了版本实测通过后再放宽。
 *
 * <p>版本按点号分段、逐段比数字：段数不同时少的那边补 0，所以 3.25.69.1979 在 3.25.69 之后、3.26 之前；
 * 一段里数字后面带的字母（69beta）只看前面的数字。
 *
 * @param lowest 范围的下界，含
 * @param below  范围的上界，不含
 */
public record VersionRange(String lowest, String below) {

    public VersionRange {
        Objects.requireNonNull(lowest, "lowest");
        Objects.requireNonNull(below, "below");
        if (lowest.isBlank() || below.isBlank()) {
            throw new IllegalArgumentException("版本范围的上下界都要写：" + lowest + " / " + below);
        }
        if (compare(lowest, below) >= 0) {
            throw new IllegalArgumentException("版本范围的下界要小于上界：[" + lowest + ", " + below + ")");
        }
    }

    /** 装的这个版本在不在范围内；版本不明（空）算不在。 */
    public boolean contains(String version) {
        if (version == null || version.isBlank()) {
            return false;
        }
        return compare(version, lowest) >= 0 && compare(version, below) < 0;
    }

    /** 给日志看的写法：[3.25.69, 3.26)。 */
    public String describe() {
        return "[" + lowest + ", " + below + ")";
    }

    // 逐段比数字；短的补 0。段之间的分隔认点号、减号、加号，所以 1.21.1-3.25.69 也能拆开。
    static int compare(String first, String second) {
        String[] a = first.trim().split("[.+-]");
        String[] b = second.trim().split("[.+-]");
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int left = i < a.length ? leadingNumber(a[i]) : 0;
            int right = i < b.length ? leadingNumber(b[i]) : 0;
            if (left != right) {
                return Integer.compare(left, right);
            }
        }
        return 0;
    }

    private static int leadingNumber(String segment) {
        int end = 0;
        while (end < segment.length() && Character.isDigit(segment.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(segment.substring(0, end));
        } catch (NumberFormatException tooLong) {
            return Integer.MAX_VALUE;
        }
    }
}
