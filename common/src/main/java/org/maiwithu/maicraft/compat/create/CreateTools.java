// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 机械动力手持工具的模组读写接缝：哪些方块能用扳手潜行右键整块拆下，砂纸能把什么磨成什么，
 * 强力胶粘一片（照 Create 两下选区确认时发的那一包）与那一片粘上没有，铺轨的起点与两端铺上没有，
 * 附近的工具箱里有什么、照工具箱轮盘那样把一格挂到快捷栏上。
 * 砂纸的配方是游戏配方表里的一类（Create 自己的类型），客户端有同步过来的一份。
 * 只用 Minecraft 与 Java 的类型；读写端在 NeoForge 模块里，测试里用替身。
 */
public interface CreateTools {

    /** 这种方块能不能用扳手整块拆下（Create 认它是可用扳手调的方块）。 */
    boolean wrenchable(BlockState state);

    /** 砂纸打磨的全部配方：拿什么磨、磨出什么、一次几件。 */
    List<Polishing> polishing(ClientLevel level);

    /** 用强力胶粘 from 到 to 这一片：Create 选区两下点完确认时发的那一包，服务器核对后扣一点胶、生成一块胶。 */
    void glue(BlockPos from, BlockPos to);

    /** from 到 to 这一片现在有没有一块正好盖住它的胶。 */
    boolean glued(ClientLevel level, BlockPos from, BlockPos to);

    /** 是不是轨道方块（铺轨的一端）。 */
    boolean track(BlockState state);

    /** 手里这件轨道记着的起点（右键过一格轨道之后）；没记为空。 */
    Optional<BlockPos> trackStart(ItemStack held);

    /** from 与 to 两格轨道之间有没有铺成的一段：两头的轨道都记着对方。 */
    boolean trackConnected(ClientLevel level, BlockPos from, BlockPos to);

    /** 角色够得着的工具箱（服务器配置的范围内，近的在前）与每一格里装着什么。 */
    List<Toolbox> toolboxes(ClientLevel level, Player player);

    /** 工具箱够得着的范围（格）：服务器配置 toolboxRange，读不到按默认 10。 */
    int toolboxRange();

    /** 照工具箱轮盘选一格那样，把 box 的第 compartment 格挂到快捷栏第 hotbarSlot 格（0~8）。 */
    void equipFromToolbox(BlockPos box, int compartment, int hotbarSlot);

    /** 一个工具箱：在哪、八格各装着什么（空格不列）。 */
    record Toolbox(BlockPos pos, List<Compartment> compartments) {
        public Toolbox {
            pos = Objects.requireNonNull(pos, "pos").immutable();
            compartments = List.copyOf(compartments);
        }
    }

    /**
     * 工具箱的一格：第几格（0~7）、认的是哪种物品、现有几件、这种物品一组几件。
     */
    record Compartment(int index, String itemId, int count, int maxStack) {
        public Compartment {
            Objects.requireNonNull(itemId, "itemId");
        }
    }

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
