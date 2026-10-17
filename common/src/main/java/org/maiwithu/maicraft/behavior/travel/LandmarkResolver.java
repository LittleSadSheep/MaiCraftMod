// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.maiwithu.maicraft.behavior.perception.SceneSign;
import org.maiwithu.maicraft.behavior.permission.ReadsRememberedPlaces;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 地标解析：把目标对象里的地标名落成一个位置，去走、去用、去存、去开车、去操作的能力共用这一处。
 *
 * <p>记住的地点优先；没记过再看此刻看得见的告示牌，恰好一块写着这个名字就落在那块牌子所在的格子。
 * 几块写着同一个名字、都挨着最近那块（门两边、柱子上下）算同一处，取最近的；隔得开的不替 LLM 挑，
 * 列出编号让它用观察编号指一块。保护名单、施工锚点不经过这里：那里只认记住的地点。只读、给结论，不动角色。
 */
public final class LandmarkResolver {

    /** 写着同一个名字的几块牌子都在最近那块这么多格以内，算同一处路牌。 */
    static final double SAME_PLACE_BLOCKS = 3.0;

    private LandmarkResolver() {
    }

    /** 解析的结局：落出了位置，或落不了并说清为什么。 */
    public sealed interface Resolution {

        /** 落出了位置：记住的地点，或唯一写着这个名字的那块牌子所在的格子。 */
        record Found(WorldPosition position) implements Resolution {}

        /**
         * 落不了：没记过、看得见的牌子上也没写着它，或几块隔开的牌子都写着它。
         *
         * @param reason     给 LLM 的一句话，各能力原样放进问题或提问里
         * @param suggestion 几块牌子都写着它时让 LLM 用观察编号指一块；没有牌子时为 null，由能力给它自己的建议
         */
        record Missing(String reason, String suggestion) implements Resolution {

            /** 有牌子相关的建议就用它，否则用能力自己的。 */
            public String suggestionOr(String fallback) {
                return suggestion != null ? suggestion : fallback;
            }

            /** 原因连着牌子相关的建议说成一句：给只放得下一句话的提问和"没找到"用。 */
            public String sentence() {
                return suggestion == null ? reason : reason + "；" + suggestion;
            }
        }
    }

    /**
     * 解析一个地标名。
     *
     * @param places 记住的地点
     * @param signs  此刻场景里看得见的告示牌；只在没记过时才读
     */
    public static Resolution resolve(String name, ReadsRememberedPlaces places, Supplier<List<SceneSign>> signs) {
        // 记住的地点优先：主播记过的"仓库"不会被路边一块同名的牌子顶掉。
        var remembered = places.place(name);
        if (remembered.isPresent()) {
            return new Resolution.Found(remembered.get());
        }
        // 没记过：抬头看看附近看得见的牌子，按名字认。还没收到字的牌子不知道写了什么，不算。
        List<SceneSign> named = new ArrayList<>();
        for (SceneSign sign : signs.get()) {
            if (sign.lines() != null && sign.lines().reads(name)) {
                named.add(sign);
            }
        }
        if (named.isEmpty()) {
            return new Resolution.Missing("没记住叫「" + name + "」的地方，附近看得见的告示牌上也没有写着「" + name + "」的", null);
        }
        SceneSign nearest = named.stream().min(Comparator.comparingInt(SceneSign::distance)).orElseThrow();
        // 一块，或几块都挨着最近那块（门两边各钉一块）：那是同一处，就去最近那块。
        boolean onePlace = named.stream().allMatch(sign -> blocksBetween(sign.position(), nearest.position()) <= SAME_PLACE_BLOCKS);
        if (onePlace) {
            return new Resolution.Found(nearest.position());
        }
        // 几块隔得开：两处基地各有一块"仓库"时挑错了就白跑一趟，列出来让 LLM 指一块。
        String listed = named.stream()
                .sorted(Comparator.comparingInt(SceneSign::distance))
                .map(sign -> sign.id() + "（" + sign.compass() + " " + sign.distance() + " 格）")
                .collect(Collectors.joining("、"));
        return new Resolution.Missing("没记住叫「" + name + "」的地方，附近有 " + named.size() + " 块告示牌都写着「"
                + name + "」：" + listed + "，不替你挑", "用 seen 指其中一块，或改用坐标");
    }

    private static double blocksBetween(WorldPosition a, WorldPosition b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
