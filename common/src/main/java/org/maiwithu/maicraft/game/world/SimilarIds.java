// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import java.util.Locale;
import java.util.Optional;

/**
 * 写错的注册 ID 找一个相近的名字，接在报错末尾当"你是不是要"；只是提示，不替 LLM 改目标。
 * 物品、方块、实体类型、群系、结构写错时都经这里说同一句话，LLM 下一次照着改就能写对。
 */
public final class SimilarIds {

    private SimilarIds() {}

    /**
     * 在候选里找和写错的 ID 最像的那个。只比较冒号后面的名字：名字完全一样（只是命名空间写错）记零分，
     * 互相包含记一分，共有一个五个字母以上的词记两分，其余按要改几个字符算。分数越低越像，同分保留先遇到的；
     * 最像的那个也超过 max(2, 名字长度 / 3) 就算一个都不像，给空。
     *
     * <p>{@code #} 开头的是标签：候选应当是不带 {@code #} 的标签 ID，给出的建议补上 {@code #}。
     *
     * @param candidates 本实例注册过的 ID（命名空间:名字）
     * @param input      写错的 ID 或 # 标签
     */
    public static Optional<String> closest(Iterable<String> candidates, String input) {
        boolean tag = input.startsWith("#");
        String want = pathOf(tag ? input.substring(1) : input);
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            String path = pathOf(candidate);
            int distance;
            if (path.equals(want)) {
                // 例如 Create 的扳手写成了 minecraft:wrench：名字一字不差，只是命名空间写错，换成 create:wrench 就对。
                distance = 0;
            } else if (want.contains(path) || path.contains(want)) {
                // 例如 woodland_mansion 包含 mansion，就认为它们很接近。
                distance = 1;
            } else if (sharesMeaningfulWord(want, path)) {
                // 例如 jungle_temple 和 jungle_pyramid 都带 jungle，虽然后半段不同，也给出提示。
                distance = 2;
            } else {
                distance = editDistance(want, path);
            }
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        int threshold = Math.max(2, want.length() / 3);
        if (best == null || bestDistance > threshold) {
            return Optional.empty();
        }
        return Optional.of(tag ? "#" + best : best);
    }

    /**
     * ID 不存在的统一说法，例如"物品 minecraft:diamon 不存在；你是不是要 minecraft:diamond"；
     * 找不到相近的就只说不存在，不硬凑一个。
     *
     * @param what    说的是哪一类，例如"物品""群系""留意清单里的"
     * @param idOrTag LLM 写的那个 ID 或 # 标签，原样写回去
     * @param similar 相近的名字
     */
    public static String missing(String what, String idOrTag, Optional<String> similar) {
        return what + " " + idOrTag + " 不存在" + similar.map(name -> "；你是不是要 " + name).orElse("");
    }

    /** 按下划线拆名字，只拿长度至少五个字符的相同词作为线索：太短的词（of、red）碰巧相同不说明像。 */
    private static boolean sharesMeaningfulWord(String a, String b) {
        for (String wordA : a.split("_")) {
            if (wordA.length() < 5) continue;
            for (String wordB : b.split("_")) {
                if (wordA.equals(wordB)) return true;
            }
        }
        return false;
    }

    private static String pathOf(String id) {
        int colon = id.indexOf(':');
        return (colon >= 0 ? id.substring(colon + 1) : id).toLowerCase(Locale.ROOT);
    }

    // 把一个名字改成另一个名字至少要增、删或替换几个字符；只保存前一行，不建整张表。
    static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int replace = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(replace, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
