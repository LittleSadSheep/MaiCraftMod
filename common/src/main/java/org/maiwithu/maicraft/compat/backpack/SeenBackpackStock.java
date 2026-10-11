// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.backpack;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * 上次开包读到的存货：角色开过一次随身背包，就记下那一刻里面各有多少东西。
 * 下次要东西时不用先开一遍包才知道有没有；但包里的东西会因腾地方、取放而变，
 * 记下的数只当线索，取的时候以现场为准，隔久了就当没看过。
 *
 * <p>只在这次启动内有效，不存盘：重开游戏后第一次要东西时，按"包里可能有、不知道有多少"报。
 */
public final class SeenBackpackStock {

    /** 记下的开包读数多久内还算数。包里的东西会变，五分钟前看到的只能当大概；类别：玩家常识。 */
    public static final Duration FRESH_FOR = Duration.ofMinutes(5);

    private Sighting sighting;

    /** 开包读到了主存储的内容之后记一笔，盖掉以前的记录。 */
    public void saw(Map<String, Integer> stacksByItem, Instant when) {
        sighting = new Sighting(Map.copyOf(new LinkedHashMap<>(stacksByItem)), when);
    }

    /** 上次开包读到的存货；没开过或已经过了 {@link #FRESH_FOR} 都给空。 */
    public Optional<Sighting> fresh(Instant now) {
        if (sighting == null || sighting.seenAt().plus(FRESH_FOR).isBefore(now)) {
            return Optional.empty();
        }
        return Optional.of(sighting);
    }

    /** 开包忘了关（被打断、界面被顶掉）读数没记成：清掉旧数，别照着数不去看。 */
    public void forget() {
        sighting = null;
    }

    /**
     * 一次开包读到的存货。
     *
     * @param stacksByItem 物品 ID → 主存储里的件数，只记有货的
     * @param seenAt       看到的时刻
     */
    public record Sighting(Map<String, Integer> stacksByItem, Instant seenAt) {
        public Sighting {
            Objects.requireNonNull(stacksByItem, "stacksByItem");
            Objects.requireNonNull(seenAt, "seenAt");
            stacksByItem = Map.copyOf(stacksByItem);
        }

        /** 想要的东西包里有几件：wanted 按物品 ID 回答算不算想要的（标签由调用方查好）。 */
        public int count(Predicate<String> wanted) {
            int total = 0;
            for (Map.Entry<String, Integer> entry : stacksByItem.entrySet()) {
                if (wanted.test(entry.getKey())) total += entry.getValue();
            }
            return total;
        }
    }
}
