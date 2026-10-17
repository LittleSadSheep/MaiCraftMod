// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 补种的执行接缝：收掉一株作物后，把种子种回原来的格子——收完把种子种回去是玩家的常识。
 * 种子从这次收获里出：背包里有这种作物的种子就种，没有就不补，不额外去找。
 * 这个动作自身保证：没有种子时照常做完（结论里写"没有种子，没补"），
 * 不以失败收场——补不成不能让已经收进背包的庄稼白收。
 * 对着哪格种、种子认哪种作物，由游戏接口层读作物自己的规则，测试用替身。
 */
public interface ReplantsCrops {

    /**
     * 为一株刚收掉的作物生成补种动作：做完时要么种回去了、要么写明没种子没补。
     * 要在掉落物捡进包之后再问——种子从这次收获里出，收割前格子还没空出来，也问不出补种。
     * 作物不是能补种的那类（甘蔗、竹子这类不是种在耕地上的）时返回 empty，由调用方跳过补种。
     *
     * @param harvestedSpot 收掉的那一格
     * @param cropType      收掉的作物方块 ID：种哪种种子按它认
     */
    Optional<Action> replant(BlockPos harvestedSpot, String cropType);
}
