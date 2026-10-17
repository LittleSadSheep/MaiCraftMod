// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;

/**
 * 无线红石信号终端的设置项，纯函数：link.frequency.1、link.frequency.2 两个频率格放什么（物品 ID，空着写 empty），
 * link.mode 收还是发（receive / transmit）。两个频率格放的东西都一样的终端互相连着。
 *
 * <p>改频率：手持那样东西（或空着手）点那一个频率格；改收发：空着手潜行右键终端。
 */
final class LinkSettings {

    static final String FREQUENCY = "link.frequency.";
    static final String MODE = "link.mode";
    static final String RECEIVE = "receive";
    static final String TRANSMIT = "transmit";
    static final String EMPTY = "empty";

    private LinkSettings() {
    }

    /** 三项设置的现值；不是无线红石信号终端时没有。 */
    static List<MachineSetting> of(Optional<CreateStations.LinkReading> reading) {
        if (reading.isEmpty()) return List.of();
        List<MachineSetting> settings = new ArrayList<>();
        for (int i = 0; i < reading.get().frequencies().size(); i++) {
            settings.add(new MachineSetting(FREQUENCY + (i + 1), itemValue(reading.get().frequencies().get(i)), List.of(),
                    "第 " + (i + 1) + " 个频率格放什么：写物品 ID，手持它点那一格；两格都一样的终端互相连着"));
        }
        settings.add(new MachineSetting(MODE, reading.get().receiver() ? RECEIVE : TRANSMIT, List.of(RECEIVE, TRANSMIT),
                "receive 收信号（输出红石），transmit 发信号（读旁边的红石）；空着手潜行右键它换"));
        return settings;
    }

    /** 频率格的读数：空着是 empty，不然是物品 ID。 */
    static String itemValue(ItemStack stack) {
        return stack.isEmpty() ? EMPTY : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** 键是第几个频率格（从 0 起）；不是频率格的键给空。 */
    static Optional<Integer> frequencyIndex(String key) {
        if (!key.startsWith(FREQUENCY)) return Optional.empty();
        String rest = key.substring(FREQUENCY.length());
        return rest.equals("1") ? Optional.of(0) : rest.equals("2") ? Optional.of(1) : Optional.empty();
    }
}
