// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

/**
 * 读机械动力传送带的模组读写接缝：一格传送带属于哪一条（控制格）、这条有多长、它在第几格，
 * 手里的传送带记着的第一根轴，两根轴之间按 Create 自己的规则能不能连，以及服务器配置的最长几格。
 *
 * <p>传送带是用传送带物品先点一根轴、再点另一根轴装上的：第一下只在手里那件物品上记下第一根轴，
 * 第二下才在两根轴之间铺出整条带。只用 Minecraft 与 Java 的类型；读写端在 NeoForge 模块里，测试里用替身。
 */
public interface CreateBelts {

    /** 这一格的传送带读数；不是传送带、没加载给空。 */
    Optional<Segment> segment(ClientLevel level, BlockPos at);

    /** 手里这件传送带物品记着的第一根轴；还没点过第一下、或不是传送带物品给空。 */
    Optional<BlockPos> firstShaft(ItemStack held);

    /** 两根轴之间按 Create 的规则现在能不能连：都加载、同轴向、直线或四十五度斜、中间是空的或同轴向的轴、不超长。 */
    boolean canConnect(ClientLevel level, BlockPos first, BlockPos second);

    /** 服务器配置里一条传送带最长几格（两根轴之间的距离必须小于它）。 */
    int maxLength();

    /** 这一格方块是不是 Create 的轴（传送带两端要的那种）。 */
    boolean shaft(ClientLevel level, BlockPos at);

    /**
     * 一格传送带。
     *
     * @param controller 这条带的控制格（第一格），同一条带每格都一样
     * @param length     整条带有几格
     * @param index      这一格是第几格（从 0 起，控制格是 0）
     * @param part       这一格是带头、带尾、中间还是装着带轮（start / end / middle / pulley）
     */
    record Segment(BlockPos controller, int length, int index, String part) {
        public Segment {
            controller = Objects.requireNonNull(controller, "controller").immutable();
            Objects.requireNonNull(part, "part");
        }
    }
}
