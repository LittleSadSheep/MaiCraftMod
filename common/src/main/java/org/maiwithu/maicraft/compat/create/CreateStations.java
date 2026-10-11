// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

/**
 * 读机械动力"看得见的东西"的模组读写接缝：置物台台面上放着什么、工作盆里装着什么、传送带上在送什么、
 * 库存请求器上次读到的现货、机械手手里拿着什么与是"使用"还是"击打"。这些都是 Create 画在世界里、或同步给客户端显示的，玩家看得见，不算透视。
 * 只用 Minecraft 与 Java 的类型；读写端在 NeoForge 模块里，测试里用替身。
 */
public interface CreateStations {

    /** 置物台（含弹射置物台）上的东西：台面那一叠，加上加工多出来的产物；不是置物台、没加载给空。 */
    Optional<List<ItemStack>> depotItems(ClientLevel level, BlockPos at);

    /** 工作盆里的物品：待加工的与做好的都算；不是工作盆、没加载给空。 */
    Optional<List<ItemStack>> basinItems(ClientLevel level, BlockPos at);

    /** 分液池上正在倒的（或倒空了还留着的）那一件；不是分液池、没加载给空。 */
    Optional<List<ItemStack>> drainItems(ClientLevel level, BlockPos at);

    /** 这一格所在的整条传送带上正在送的东西；不是传送带、没加载给空。 */
    Optional<List<ItemStack>> beltItems(ClientLevel level, BlockPos at);

    /** 库存请求器上次读到的网络现货；不是库存请求器、没加载给空；还没读过时是空表加上"多少刻前"。 */
    Optional<StockSnapshot> stock(ClientLevel level, BlockPos at);

    /** 这一格里装着的流体：流体储罐（整座罐）、注液器、分液池、工作盆的流体格；没有流体格给空表。 */
    List<FluidReading> fluids(ClientLevel level, BlockPos at);

    /** 显示面上的字：辉光管、翻牌显示板的每一行，告示框里放的东西；不是显示面给空。 */
    Optional<String> shownText(ClientLevel level, BlockPos at);

    /** 无线红石信号终端：两个频率格放的东西、能点在哪、是收还是发、通不通电；不是它给空。 */
    Optional<LinkReading> link(ClientLevel level, BlockPos at);

    /** 装置在不在动：机械与时钟轴承、机械活塞、绳索滑轮、电梯滑轮；不是这几种给空。 */
    Optional<Boolean> moving(ClientLevel level, BlockPos at);

    /** 一格流体：流体 ID、多少毫桶、能装多少（读不到容量为 0）。 */
    record FluidReading(String fluidId, int amount, int capacity) {
        public FluidReading {
            Objects.requireNonNull(fluidId, "fluidId");
        }
    }

    /**
     * 无线红石信号终端的读数。
     *
     * @param frequencies 两个频率格放的东西（第一格、第二格），空着是空物品
     * @param spots       两个频率格能点在哪（世界坐标的中心与半径）
     * @param receiver    是收信号（true）还是发信号
     * @param powered     现在通不通电
     */
    record LinkReading(List<ItemStack> frequencies, List<CreateValues.Spot> spots, boolean receiver, boolean powered) {
        public LinkReading {
            frequencies = List.copyOf(frequencies);
            spots = List.copyOf(spots);
        }
    }

    /** 机械手手里拿着的东西（画在它手上）与模式；不是机械手、没加载给空，手里那件读不到时给空物品。 */
    Optional<DeployerReading> deployer(ClientLevel level, BlockPos at);

    /**
     * 机械手的读数。
     *
     * @param held 手里拿着的东西；空手是空物品
     * @param mode use 使用（右键）、punch 击打（左键）；读不到为空串
     */
    record DeployerReading(ItemStack held, String mode) {
        public DeployerReading {
            Objects.requireNonNull(held, "held");
            Objects.requireNonNull(mode, "mode");
        }
    }

    /**
     * 库存请求器的一份现货摘要。只有现货：Create 在界面里给出的"能合成多少"的预览不算。
     *
     * @param entries         每种东西有几件
     * @param ticksSinceUpdate 这份摘要是多少刻前收到的
     */
    record StockSnapshot(List<StockEntry> entries, int ticksSinceUpdate) {
        public StockSnapshot {
            entries = List.copyOf(entries);
        }
    }

    /** 现货里的一种东西：物品 ID 与件数（件数可以超过一组）。 */
    record StockEntry(String itemId, long count) {
        public StockEntry {
            Objects.requireNonNull(itemId, "itemId");
        }
    }
}
