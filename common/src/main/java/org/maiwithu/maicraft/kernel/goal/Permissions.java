// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import java.util.Objects;
import java.util.Set;

/**
 * 许可：这次任务里，角色为了把事做成可以改变世界到什么程度。不是游戏或服务器的 OP 权限。
 *
 * <p>默认值是"像正常玩家一样"：可以挖天然的土、石、木，搭临时方块并在用完后收回，
 * 主动处理敌对生物，为了具体需要（吃的、羊毛、皮革）处理野生动物；玩家的东西一律不碰，不用稀有消耗品。
 * 本类只表达许可的取值；"这一格能不能动"的判断在行为层的许可与保护模型里，全仓只有那一个检查点。
 *
 * @param changeBlocks       能改变哪些方块
 * @param fight              战斗的主动程度
 * @param useRareItems       能否使用稀有消耗品（末影珍珠、不死图腾、钻石……）
 * @param killAnimals        能否为了具体需要击杀动物
 * @param survivalNeeds      是否处理生存需求；只有寻死这类明确的任务可以关闭
 * @param protectedLandmarks 额外不许碰的地标名
 */
public record Permissions(
        BlockChanges changeBlocks,
        Fight fight,
        boolean useRareItems,
        AnimalKilling killAnimals,
        SurvivalNeeds survivalNeeds,
        Set<String> protectedLandmarks) {

    /** 默认值：像正常玩家一样。 */
    public static final Permissions DEFAULT = new Permissions(
            BlockChanges.NATURAL, Fight.HOSTILE_MOBS, false, AnimalKilling.WILD, SurvivalNeeds.ON, Set.of());

    public Permissions {
        Objects.requireNonNull(changeBlocks, "changeBlocks");
        Objects.requireNonNull(fight, "fight");
        Objects.requireNonNull(killAnimals, "killAnimals");
        Objects.requireNonNull(survivalNeeds, "survivalNeeds");
        protectedLandmarks = protectedLandmarks == null ? Set.of() : Set.copyOf(protectedLandmarks);
    }

    /** 能改变哪些方块。 */
    public enum BlockChanges {
        /** 不改变任何方块。 */
        NONE,
        /** 只搭临时方块，用完收回。 */
        TEMPORARY,
        /** 天然方块，以及不属于任何玩家的东西（村庄的床、废弃矿井的铁轨……，放在最后考虑）。 */
        NATURAL,
        /** 一切不受保护的方块。 */
        ANY
    }

    /** 战斗的主动程度。 */
    public enum Fight {
        /** 只自卫：被打才还手。 */
        SELF_DEFENSE,
        /** 主动处理敌对生物。 */
        HOSTILE_MOBS,
        /** 可以攻击指定的任何目标。 */
        ANY
    }

    /** 能否为了具体需要击杀动物；剪毛、挤奶不算伤害。 */
    public enum AnimalKilling {
        /** 不击杀动物。 */
        NONE,
        /** 野生、没起名、没驯服、没拴绳、不在围栏里的普通动物。 */
        WILD,
        /** 不受保护的任何动物。 */
        ANY
    }

    /** 是否处理生存需求（吃饭、睡觉、自救）。 */
    public enum SurvivalNeeds {
        ON,
        OFF
    }
}
