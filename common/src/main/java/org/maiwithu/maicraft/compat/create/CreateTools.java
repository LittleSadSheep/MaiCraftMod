// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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

    /** 扳手（不潜行）点这一面会把它转成什么样子；不是 Create 可调的方块为空。 */
    default Optional<BlockState> wrenched(BlockState state, Direction face) {
        return Optional.empty();
    }

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

    /** 土豆加农炮能不能打这件、打出去什么样：读 Create 的弹药类型注册表（数据包可改）；不能打为空。 */
    Optional<PotatoAmmo> potatoAmmo(Player player, ItemStack ammo);

    /**
     * 一种土豆加农炮弹药的数值（PotatoCannonProjectileType）。
     *
     * @param reloadTicks        打完冷却几刻
     * @param split              一发分成几颗
     * @param drag               每刻乘的减速
     * @param velocityMultiplier 出膛速度倍数（基准每刻 2 格）
     * @param gravityMultiplier  重力倍数（基准每刻 0.05）
     */
    record PotatoAmmo(int reloadTicks, int split, float drag, float velocityMultiplier, float gravityMultiplier) {
    }

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

    /**
     * 按下或松开手里遥控器（机械动力的无线遥控器）的按键：和遥控器启用后按方向键、跳跃、潜行时发的一样。
     * 按住期间要每隔几刻再发一次"还按着"，不然收端过一会儿当松开了。
     *
     * @param buttons 按键序号：0 前、1 后、2 左、3 右、4 跳、5 潜行
     */
    void remoteButtons(List<Integer> buttons, boolean press);

    /**
     * 这个遥控器六个键各绑的频率：每个键两格（没放东西为空物品），顺序是上、下、左、右、跳、潜行；不是遥控器为空列表。
     * 和机械动力按键时算频率用的是同一份数据。
     */
    List<List<ItemStack>> remoteFrequencies(ItemStack controller);

    /** 无线红石能连多远（格）：机械动力服务端配置，读不到按默认的 256。 */
    int linkRange();
}
