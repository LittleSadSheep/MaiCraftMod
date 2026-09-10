package org.maiwithu.maicraft.core.task;

import net.minecraft.resources.ResourceLocation;

import java.util.stream.Stream;

/**
 * 输入的资源名字不存在时，找一个看起来相近的名字作为提示；不会自动替用户改目标。
 */
public final class IdSuggest {

    private IdSuggest() {}

    /**
     * 只比较冒号后面的名字：包含关系记一分，共有较长单词记两分，其余按改几个字符来比较。
     * 分数越低越优先，同分保留先遇到的。当前完全同名也走包含分支，因此不一定胜过较长名字。
     */
    public static String closest(Stream<ResourceLocation> candidates, String input) {
        String want = pathOf(input);
        ResourceLocation best = null;
        int bestDist = Integer.MAX_VALUE;
        for (ResourceLocation id : (Iterable<ResourceLocation>) candidates::iterator) {
            String path = id.getPath();
            int dist;
            if (want.contains(path) || path.contains(want)) {
                // 例如 woodland_mansion 包含 mansion，就认为它们很接近。
                dist = 1;
            } else if (sharesMeaningfulToken(want, path)) {
                // 例如 jungle_temple 和 jungle_pyramid 都带 jungle，虽然后半段不同，也给出提示。
                dist = 2;
            } else {
                dist = levenshtein(want, path);
            }
            if (dist < bestDist) {
                bestDist = dist;
                best = id;
            }
        }
        int threshold = Math.max(2, want.length() / 3);
        return (best != null && bestDist <= threshold) ? best.toString() : null;
    }

    /**
     * 按下划线拆名字，只拿长度至少五个字符的相同词作为线索。
     */
    private static boolean sharesMeaningfulToken(String a, String b) {
        for (String ta : a.split("_")) {
            if (ta.length() < 5) continue;
            for (String tb : b.split("_")) {
                if (ta.equals(tb)) return true;
            }
        }
        return false;
    }

    private static String pathOf(String input) {
        int colon = input.indexOf(':');
        return (colon >= 0 ? input.substring(colon + 1) : input).toLowerCase();
    }

    // 算出把一个名字改成另一个名字至少要增、删或替换几个字符；只保存前一行，避免建立整张表。
    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int sub = prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                cur[j] = Math.min(sub, Math.min(prev[j] + 1, cur[j - 1] + 1));
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
