// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import java.util.Objects;
import java.util.Set;

/**
 * 授权：伙伴为了干活可以改变世界到什么程度（docs/design/03 的 M7）。
 *
 * <p>默认档是"讲道理的伙伴"（2026-10-08 拍板）：可以挖天然的土石木，搭临时方块并在用完后收回，
 * 主动处理敌对生物，为了具体需要（吃的、羊毛、皮革）处理野生动物；玩家的东西一律不碰，不用稀有消耗品。
 * 本类只表达授权的取值；"这一格能不能动"的判定在行为层的授权与保护模型里，全仓只有那一个检查点。
 *
 * @param terrain   能改变哪些方块
 * @param combat    战斗的主动程度
 * @param rareItems 能否使用稀有消耗品（末影珍珠、不死图腾、钻石……）
 * @param animals   能否为了具体需要击杀动物
 * @param upkeep    身体维护是否开启；只有寻死这类显式任务可以关闭
 * @param protect   额外不许碰的地标名
 */
public record Mandate(Terrain terrain, Combat combat, boolean rareItems, Animals animals, Upkeep upkeep, Set<String> protect) {

    /** 默认档：讲道理的伙伴。 */
    public static final Mandate DEFAULT =
            new Mandate(Terrain.NATURAL, Combat.HOSTILE, false, Animals.WILD, Upkeep.ON, Set.of());

    public Mandate {
        Objects.requireNonNull(terrain, "terrain");
        Objects.requireNonNull(combat, "combat");
        Objects.requireNonNull(animals, "animals");
        Objects.requireNonNull(upkeep, "upkeep");
        protect = protect == null ? Set.of() : Set.copyOf(protect);
    }

    /** 能改变哪些方块。 */
    public enum Terrain {
        /** 不改变任何方块。 */
        NONE,
        /** 只搭临时方块，用完收回。 */
        TEMPORARY,
        /** 天然方块，以及不属于任何玩家的物件（村庄的床、废弃矿井的铁轨……，放在最后考虑）。 */
        NATURAL,
        /** 一切不受保护的格子。 */
        ANY
    }

    /** 战斗的主动程度。 */
    public enum Combat {
        /** 只自卫：被打才还手。 */
        DEFEND,
        /** 主动处理敌对生物。 */
        HOSTILE,
        /** 可以攻击指定的任何目标。 */
        ANY
    }

    /** 能否为了具体需要击杀动物；剪毛、挤奶不算伤害。 */
    public enum Animals {
        /** 不击杀动物。 */
        NONE,
        /** 野生、无名、未驯服、未拴绳、不在围栏里的普通动物。 */
        WILD,
        /** 不受保护的任何动物。 */
        ANY
    }

    /** 身体维护（吃饭、睡觉、自救）是否开启。 */
    public enum Upkeep {
        ON,
        OFF
    }
}
