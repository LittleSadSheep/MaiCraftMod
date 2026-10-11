// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;

/**
 * 样板供应器里放哪些样板，纯函数：每一种样板按它主要产出的物品记成一项 pattern.产出物品，
 * 值是 in（在供应器里）或 out（不在，在背包里）。身上带着的样板与上次打开界面时看见的样板都列出来，
 * LLM 写 {"pattern.minecraft:iron_block": "in"} 就是把产出铁块的那张样板放进去。
 *
 * <p>供应器里的样板只在开着界面时同步：读数是上次打开时看见的，没打开看过写 unknown。
 */
final class PatternSettings {

    static final String PREFIX = "pattern.";
    static final String IN = "in";
    static final String OUT = "out";
    static final String UNKNOWN = "unknown";

    private PatternSettings() {
    }

    /**
     * @param inside  上次打开界面时供应器里样板的产出；没打开看过为空
     * @param carried 身上带着的样板的产出
     */
    static List<MachineSetting> of(Optional<List<String>> inside, Set<String> carried) {
        Set<String> outputs = new LinkedHashSet<>(inside.orElse(List.of()));
        outputs.addAll(carried);
        List<MachineSetting> settings = new ArrayList<>();
        for (String output : outputs) {
            String value = inside.map(list -> list.contains(output) ? IN : OUT).orElse(UNKNOWN);
            settings.add(new MachineSetting(PREFIX + output, value, List.of(IN, OUT),
                    "产出 " + output + " 的样板：in 放进供应器，out 拿回背包；供应器里有什么要开着界面才看得见，读数是上次打开时看见的"));
        }
        return settings;
    }

    /** 键里的产出物品；不是 pattern. 开头的键给空。 */
    static Optional<String> outputOf(String key) {
        return key.startsWith(PREFIX) && key.length() > PREFIX.length()
                ? Optional.of(key.substring(PREFIX.length())) : Optional.empty();
    }
}
