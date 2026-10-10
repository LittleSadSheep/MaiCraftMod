// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * 上次看到的网络存货：角色每次点开一台 ME 终端，就记下那一刻网络连没连上、里面各有多少东西。
 * 下次有人要东西，不用先走过去开一遍才知道有没有；但网络里的东西一直在被机器和别人搬，
 * 记下的数只当线索，到了终端跟前以现场为准，隔久了就当没看过。
 *
 * <p>只在这次启动内有效，不存盘：重开游戏后第一次要东西时，终端按"没看过、不知道有多少"报。
 * 网络之间看不出谁连着谁，所以按终端分开记，一台终端看到的不算到别的终端头上。
 */
public final class SeenNetworkStock {

    /** 记下的网络存货多久内还算数。AE 网络里的东西一直在变，五分钟前看到的只能当大概；类别：玩家常识。 */
    public static final Duration FRESH_FOR = Duration.ofMinutes(5);

    private final Map<TerminalAt, Sighting> sightings = new HashMap<>();

    /**
     * 点开一台终端、网络存货同步过来之后记一笔，盖掉这台终端以前的记录。
     *
     * @param dimension 终端所在的维度
     * @param terminal  哪一台终端
     * @param linked    界面显示网络连没连上；没连上时 stock 一般是空的
     * @param stock     界面里的网络存货
     * @param when      看到的时刻
     */
    public void saw(String dimension, Ae2Terminals.Terminal terminal, boolean linked,
            List<Ae2TerminalMenu.StockEntry> stock, Instant when) {
        Map<String, Long> storedByItem = new LinkedHashMap<>();
        for (Ae2TerminalMenu.StockEntry entry : stock) {
            if (entry.stored() <= 0) continue;
            // 同一种物品的几个变体（耐久、附魔不同）合在一起记：要东西时按物品 ID 或标签认，不细分变体。
            String itemId = BuiltInRegistries.ITEM.getKey(entry.sample().getItem()).toString();
            storedByItem.merge(itemId, entry.stored(), Long::sum);
        }
        sightings.put(TerminalAt.of(dimension, terminal), new Sighting(linked, Map.copyOf(storedByItem), when));
    }

    /** 这台终端上次看到的样子；没看过或已经过了 {@link #FRESH_FOR} 都给空。 */
    public Optional<Sighting> lastSeen(String dimension, Ae2Terminals.Terminal terminal, Instant now) {
        Sighting sighting = sightings.get(TerminalAt.of(dimension, terminal));
        if (sighting == null || sighting.seenAt().plus(FRESH_FOR).isBefore(now)) {
            return Optional.empty();
        }
        return Optional.of(sighting);
    }

    /** 那一格那一面的终端不在了（被拆、换了别的部件）：忘掉它的记录，免得照着旧数去找。 */
    public void forget(String dimension, BlockPos block, Direction side) {
        sightings.remove(new TerminalAt(dimension, block.immutable(), side));
    }

    /**
     * 一台终端某一刻的样子。
     *
     * @param linked       网络连没连上
     * @param storedByItem 物品 ID → 网络里的件数（各变体合计），只记有货的
     * @param seenAt       看到的时刻
     */
    public record Sighting(boolean linked, Map<String, Long> storedByItem, Instant seenAt) {
        public Sighting {
            Objects.requireNonNull(storedByItem, "storedByItem");
            Objects.requireNonNull(seenAt, "seenAt");
            storedByItem = Map.copyOf(storedByItem);
        }

        /** 想要的东西网络里有几件：wanted 按物品 ID 回答算不算想要的（标签由调用方查好）。 */
        public long count(Predicate<String> wanted) {
            long total = 0;
            for (Map.Entry<String, Long> entry : storedByItem.entrySet()) {
                if (wanted.test(entry.getKey())) total += entry.getValue();
            }
            return total;
        }
    }

    /** 一台终端的身份：维度、格子与哪一面。 */
    private record TerminalAt(String dimension, BlockPos block, Direction side) {
        static TerminalAt of(String dimension, Ae2Terminals.Terminal terminal) {
            return new TerminalAt(dimension, terminal.block(), terminal.side());
        }
    }
}
