// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.List;
import java.util.Objects;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 机械动力手持工具的模组读写接缝：哪些方块能用扳手潜行右键整块拆下，砂纸能把什么磨成什么。
 * 砂纸的配方是游戏配方表里的一类（Create 自己的类型），客户端有同步过来的一份。
 * 只用 Minecraft 与 Java 的类型；读写端在 NeoForge 模块里，测试里用替身。
 */
public interface CreateTools {

    /** 这种方块能不能用扳手整块拆下（Create 认它是可用扳手调的方块）。 */
    boolean wrenchable(BlockState state);

    /** 砂纸打磨的全部配方：拿什么磨、磨出什么、一次几件。 */
    List<Polishing> polishing(ClientLevel level);

    /**
     * 一条砂纸打磨的配方：一次磨一件原料，出一份产物。
     *
     * @param inputs     能当原料的物品（任一件都行）
     * @param result     磨出来的物品
     * @param resultCount 一次出几件
     */
    record Polishing(List<String> inputs, String result, int resultCount) {
        public Polishing {
            inputs = List.copyOf(inputs);
            Objects.requireNonNull(result, "result");
        }
    }
}
