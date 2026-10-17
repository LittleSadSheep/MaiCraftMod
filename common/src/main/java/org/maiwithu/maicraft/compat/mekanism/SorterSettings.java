// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.mekanism;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;

/**
 * 物流分拣器的两项设置，纯函数：sorter.item 只认哪一样物品（一条精确的单物品规则），
 * sorter.auto_eject 没有规则对得上时往不往外送（on / off）。
 *
 * <p>规则只在开着界面时同步：读数是上次打开界面时看见的，没打开看过写 unknown。
 * 只管"一条精确的单物品规则"：没有规则就新建，只有一条同物品规则就改成精确的；
 * 已经有别的规则时报冲突，不擅自删掉原来的规则。
 */
final class SorterSettings {

    static final String ITEM = "sorter.item";
    static final String AUTO_EJECT = "sorter.auto_eject";
    /** 没打开界面看过时的读数。 */
    static final String UNKNOWN = "unknown";
    /** 一条规则都没有时 sorter.item 的读数。 */
    static final String NONE = "none";

    /** 要让分拣器只认某一样物品时，按现在的规则该怎么做。 */
    enum Decision { READY, ADD, EDIT, CONFLICT }

    private SorterSettings() {
    }

    /** 两项设置的现值；没打开界面看过时读数是 unknown。 */
    static List<MachineSetting> of(Optional<MekanismReads.SorterState> seen) {
        List<MachineSetting> settings = new ArrayList<>();
        settings.add(new MachineSetting(ITEM, seen.map(SorterSettings::itemValue).orElse(UNKNOWN), List.of(),
                "只认哪一样物品：写物品 ID，分拣器只放行这一样（一条精确的单物品规则）；"
                        + "规则只在开着界面时看得见，读数是上次打开时看见的"));
        settings.add(new MachineSetting(AUTO_EJECT, seen.map(state -> state.autoEject() ? "on" : "off").orElse(UNKNOWN),
                List.of("on", "off"), "没有规则对得上的东西往不往外送：off 只送规则认的"));
        return settings;
    }

    // sorter.item 的读数：没有规则是 none；只有一条精确的单物品规则是那样物品；别的样子照实写出来。
    static String itemValue(MekanismReads.SorterState state) {
        List<MekanismReads.SorterRule> rules = state.rules();
        if (rules.isEmpty()) return NONE;
        if (rules.size() == 1 && rules.getFirst().itemRule()) {
            MekanismReads.SorterRule rule = rules.getFirst();
            return rule.exact() ? rule.itemId() : rule.itemId() + "（不是精确规则）";
        }
        return "已有 " + rules.size() + " 条别的规则";
    }

    // 没有规则就新建；只有一条同物品规则时改成精确的；多条规则或别种规则一律是冲突，不删原来的。
    static Decision decide(MekanismReads.SorterState state, String wanted) {
        List<MekanismReads.SorterRule> rules = state.rules();
        if (rules.isEmpty()) return Decision.ADD;
        if (rules.size() != 1) return Decision.CONFLICT;
        MekanismReads.SorterRule rule = rules.getFirst();
        if (!rule.itemRule() || !rule.itemId().equals(wanted)) return Decision.CONFLICT;
        return rule.exact() ? Decision.READY : Decision.EDIT;
    }
}
