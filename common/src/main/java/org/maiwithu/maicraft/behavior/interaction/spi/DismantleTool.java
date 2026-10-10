// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction.spi;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 拆卸工具的接缝：用模组自己的工具把方块整块拆下并收回物品（Create 的扳手潜行右键拆机器件）。
 * 拆也是改世界：调用方先过许可，再问工具认不认领这块；认领了就用它拆，拆完按格复查，还在就退回挖。
 * 换到主手、缺工具去拿走联动能用的玩家行为，不在这里另造。
 */
public interface DismantleTool {

    /** 这块方块能不能用模组的工具整块拆下；能就说要拿什么工具，不是它的方块为空。 */
    Optional<Item> toolFor(BlockState state);

    /** 拿着工具把 cell 这一格整块拆下、等物品进背包的动作；给不出时为空。 */
    Optional<Action> dismantle(BlockPos cell);
}
