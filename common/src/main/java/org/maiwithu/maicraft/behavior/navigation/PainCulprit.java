// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 没人打、身上却疼了一下：是碰着的哪种方块扎的。像玩家一样凭身体感受认，不靠给每个模组写名单——
 * 模组再多，碰一下掉血的东西身体感受都一样。纯函数，离线测试直接喂碰着的格子。
 *
 * <p>只认"碰着才疼"的伤：模组自己的伤害类型、原版的仙人掌、浆果丛、火、营火、岩浆块这几种；
 * 摔、淹、饿、着火烧、中毒这些和碰着的方块无关，一概不认。
 */
final class PainCulprit {

    /** 原版里"碰着方块才疼"的几种伤；别的原版伤和碰着什么无关。 */
    private static final Set<String> VANILLA_CONTACT = Set.of(
            "minecraft:cactus", "minecraft:sweet_berry_bush", "minecraft:in_fire", "minecraft:campfire",
            "minecraft:hot_floor");
    /** 名字对得上至少要这么长，免得两三个字母碰巧对上。 */
    private static final int MIN_NAME_MATCH = 4;

    private PainCulprit() {
    }

    /**
     * 疼的那一刻身体碰着的一格。
     *
     * @param blockId     方块 ID
     * @param underFoot   垫在脚下（顶面不高于脚底），是站着的地面不是扎人的东西
     * @param ruledOut    本来就不算：空气、液体、门这类通道，原版已经绕开的危险物
     * @param fullCube    整格实心：身体进不去，碰着只是贴着墙
     * @param replaceable 草、雪层这类一碰就让开的
     */
    record Touched(String blockId, boolean underFoot, boolean ruledOut, boolean fullCube, boolean replaceable) {
    }

    /**
     * 认出来的那种方块。
     *
     * @param certain 名字和伤害类型对得上（荆棘扎的伤叫荆棘），一次就记；只是唯一碰着的那种算嫌疑，再疼一次才记
     */
    record Verdict(String blockId, boolean certain) {
    }

    /** @param damageType 伤害类型的注册 ID，例如 biomesoplenty:bramble */
    static Optional<Verdict> judge(String damageType, List<Touched> touched) {
        if (!contactDamage(damageType)) return Optional.empty();
        String damageName = name(damageType);
        // 先看名字：伤害叫荆棘、碰着的方块也叫荆棘，就是它（脚下的也算，模组的烫脚地板这类）。
        for (Touched cell : touched) {
            if (cell.ruledOut()) continue;
            String blockName = name(cell.blockId());
            if (matches(blockName, damageName)) return Optional.of(new Verdict(cell.blockId(), true));
        }
        // 名字对不上：除掉脚下的地面、墙、草，只剩一种碰着的才算嫌疑；两种以上说不清是谁，不记。
        Set<String> suspects = new LinkedHashSet<>();
        for (Touched cell : touched) {
            if (cell.ruledOut() || cell.underFoot() || cell.fullCube() || cell.replaceable()) continue;
            suspects.add(cell.blockId());
        }
        return suspects.size() == 1 ? Optional.of(new Verdict(suspects.iterator().next(), false)) : Optional.empty();
    }

    // 模组自己的伤害类型多半就是碰着它的方块扎的；原版只认那几种碰着才疼的。
    private static boolean contactDamage(String damageType) {
        if (damageType == null || damageType.isEmpty()) return false;
        return !damageType.startsWith("minecraft:") || VANILLA_CONTACT.contains(damageType);
    }

    // 只比 ID 的名字部分，去掉下划线和点：bramble、sweet_berry_bush → sweetberrybush。
    private static String name(String id) {
        int colon = id.indexOf(':');
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        return path.toLowerCase(Locale.ROOT).replace("_", "").replace(".", "");
    }

    private static boolean matches(String blockName, String damageName) {
        if (blockName.length() < MIN_NAME_MATCH || damageName.length() < MIN_NAME_MATCH) return false;
        return blockName.contains(damageName) || damageName.contains(blockName);
    }
}
