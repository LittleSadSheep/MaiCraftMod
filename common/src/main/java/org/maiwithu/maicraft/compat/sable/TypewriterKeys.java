// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 无线红石打字机按键的名字和键码互换：{@code 打字机:W}、{@code 打字机:空格}、{@code 打字机:上}……
 * 无线红石打字机认的是键盘的键码（和 GLFW 的一样：字母、数字就是它的大写字符码）。纯函数。
 */
final class TypewriterKeys {

    /** 不认得的键。 */
    static final int UNKNOWN = -1;

    /** 字母数字以外的键：中文名和英文名都认。 */
    private static final Map<String, Integer> NAMED = Map.ofEntries(
            Map.entry("空格", 32), Map.entry("space", 32),
            Map.entry("上", 265), Map.entry("up", 265),
            Map.entry("下", 264), Map.entry("down", 264),
            Map.entry("左", 263), Map.entry("left", 263),
            Map.entry("右", 262), Map.entry("right", 262),
            Map.entry("回车", 257), Map.entry("enter", 257),
            Map.entry("tab", 258),
            Map.entry("退格", 259), Map.entry("backspace", 259),
            Map.entry("shift", 340), Map.entry("ctrl", 341), Map.entry("alt", 342));

    /** 键码写回名字时用的中文名。 */
    private static final Map<Integer, String> NAMES = Map.ofEntries(
            Map.entry(32, "空格"), Map.entry(265, "上"), Map.entry(264, "下"), Map.entry(263, "左"),
            Map.entry(262, "右"), Map.entry(257, "回车"), Map.entry(258, "tab"), Map.entry(259, "退格"),
            Map.entry(340, "shift"), Map.entry(341, "ctrl"), Map.entry(342, "alt"));

    private TypewriterKeys() {}

    /** 这个输入是不是写给无线红石打字机的：{@code 打字机:…} 或 {@code typewriter:…}（冒号全角半角都行）。 */
    static boolean addressed(String control) {
        int colon = colon(control);
        if (colon < 0) return false;
        // 冒号统一成半角再比：控制件的写法就是"名字:键"。
        String device = control.substring(0, colon).trim().toLowerCase(Locale.ROOT) + ":";
        return device.equals("打字机:") || device.equals("无线红石打字机:") || device.equals("typewriter:");
    }

    /** 写给无线红石打字机的输入按的是哪个键；不认得为 {@link #UNKNOWN}。 */
    static int key(String control) {
        if (!addressed(control)) return UNKNOWN;
        String name = control.substring(colon(control) + 1).trim();
        if (name.length() == 1) {
            char single = Character.toUpperCase(name.charAt(0));
            if ((single >= 'A' && single <= 'Z') || (single >= '0' && single <= '9')) return single;
        }
        Integer named = NAMED.get(name.toLowerCase(Locale.ROOT));
        return named == null ? UNKNOWN : named;
    }

    /** 键码的名字：字母数字就是它自己，别的用中文名；认不出写"键码 N"。 */
    static String name(int key) {
        if ((key >= 'A' && key <= 'Z') || (key >= '0' && key <= '9')) return String.valueOf((char) key);
        String named = NAMES.get(key);
        return named != null ? named : "键码 " + key;
    }

    /** 一串键码的名字，用顿号连起来（按名字排）。 */
    static String names(Collection<Integer> keys) {
        return keys.stream().map(TypewriterKeys::name).sorted().collect(Collectors.joining("、"));
    }

    private static int colon(String control) {
        int half = control.indexOf(':');
        int full = control.indexOf('：');
        if (half < 0) return full;
        if (full < 0) return half;
        return Math.min(half, full);
    }
}
