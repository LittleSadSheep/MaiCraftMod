// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * 挑终端：附近有好几台 ME 终端时去哪一台。纯函数，不读世界也不动手。
 *
 * <p>记得哪台终端的网络里有想要的东西，就去最近的那台，报明确的数；有确定的货就不赌运气。
 * 没有记得有货的，就去看一眼：先去没看过的（或看过已经过时的）里最近的一台，
 * 再是上次看不行的——没连上网络、网络里没有这样东西——里最近的一台，报"不知道有多少"并写明上次看到的情况。
 * 上次不行不代表现在还不行（电可能接上了、东西可能有人放进去了），旧记录只拿来排先后，不拿来拒绝；
 * 同一次拿东西里看过一次还不行，引擎不会再回头。不能取用的终端（不是自己或自家人的）不去。
 */
public final class TerminalChooser {

    private TerminalChooser() {}

    /**
     * 一台候选终端。
     *
     * @param terminal 哪一台
     * @param distance 离角色多远（格）
     * @param usable   能不能取用：自己或自家人的终端才能，由保护判断回答
     * @param seen     上次看到的网络存货；没看过或已过时为空
     */
    public record Candidate(Ae2Terminals.Terminal terminal, double distance, boolean usable,
            Optional<SeenNetworkStock.Sighting> seen) {
        public Candidate {
            Objects.requireNonNull(terminal, "terminal");
            Objects.requireNonNull(seen, "seen");
            if (distance < 0) throw new IllegalArgumentException("距离不能为负：" + distance);
        }
    }

    /** 挑的结论。 */
    public sealed interface Choice {
    }

    /** 记得这台终端的网络里有 count 件想要的东西。 */
    public record Known(Candidate candidate, long count) implements Choice {
    }

    /**
     * 不知道这台终端的网络里有什么，去了才知道。
     *
     * @param lastTime 上次看到的情况，例如"上次看没连上网络（没电或没频道）"；没看过或记录已过时为空
     */
    public record Unknown(Candidate candidate, Optional<String> lastTime) implements Choice {
        public Unknown {
            Objects.requireNonNull(candidate, "candidate");
            Objects.requireNonNull(lastTime, "lastTime");
        }
    }

    /** 哪台都不去；reason 写清附近终端各是什么情况。 */
    public record None(String reason) implements Choice {
    }

    /**
     * 从候选里挑一台。
     *
     * @param candidates   附近找到的终端
     * @param wanted       按物品 ID 回答算不算想要的（标签由调用方查好）
     * @param wantedText   想要的东西给人看的写法，写进说明里，例如"minecraft:torch"
     * @param radiusBlocks 这次找终端的半径，写进"附近没有终端"的原因里
     */
    public static Choice choose(List<Candidate> candidates, Predicate<String> wanted, String wantedText,
            int radiusBlocks) {
        if (candidates.isEmpty()) {
            return new None("附近 " + radiusBlocks + " 格内没有 ME 终端");
        }
        List<Known> known = new ArrayList<>();
        List<Candidate> unseen = new ArrayList<>();
        List<Unknown> lastTimeNot = new ArrayList<>();
        int notUsable = 0;
        for (Candidate candidate : candidates) {
            // 别人的终端不取用：和别人的箱子一样，只在原因里交代一句。
            if (!candidate.usable()) {
                notUsable++;
                continue;
            }
            if (candidate.seen().isEmpty()) {
                unseen.add(candidate);
                continue;
            }
            SeenNetworkStock.Sighting sighting = candidate.seen().get();
            if (!sighting.linked()) {
                lastTimeNot.add(new Unknown(candidate, Optional.of("上次看没连上网络（没电或没频道）")));
                continue;
            }
            long count = sighting.count(wanted);
            if (count > 0) {
                known.add(new Known(candidate, count));
            } else {
                lastTimeNot.add(new Unknown(candidate, Optional.of("上次看网络里没有" + wantedText)));
            }
        }
        if (!known.isEmpty()) {
            return known.stream().min(Comparator.comparingDouble(choice -> choice.candidate().distance()))
                    .orElseThrow();
        }
        if (!unseen.isEmpty()) {
            return new Unknown(unseen.stream().min(Comparator.comparingDouble(Candidate::distance)).orElseThrow(),
                    Optional.empty());
        }
        if (!lastTimeNot.isEmpty()) {
            return lastTimeNot.stream().min(Comparator.comparingDouble(choice -> choice.candidate().distance()))
                    .orElseThrow();
        }
        return new None("附近的 ME 终端都是别人的，不取用（" + notUsable + " 台）");
    }
}
