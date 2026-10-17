// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.List;
import java.util.Objects;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * 读、改机械动力方块上设置框的模组读写接缝：过滤格（放了什么、限多少件）与滚轮数值、选项（转速、阈值、
 * 计时、工作方式）。这些都是 Create 同步给客户端画在方块上的。改数值与选项发的是 Create 设置界面关上时发的那一包；
 * 过滤格没有这一包，要角色手持东西真去点那一格。只用 Minecraft 与 Java 的类型；读写端在 NeoForge 模块里。
 */
public interface CreateValues {

    /** 这一格方块上的全部设置框；不是带设置框的机械动力方块、没加载给空表。 */
    Boxes boxes(ClientLevel level, BlockPos at);

    /** 按 Create 设置界面关上时那样改一个数值或选项：哪一个框（网络编号）、第几行、多少。 */
    void sendValue(BlockPos at, int netId, Direction side, int row, int value);

    /** 一格方块上的设置框：过滤格与数值、选项。 */
    record Boxes(List<FilterBox> filters, List<ValueBox> values) {
        public Boxes {
            filters = List.copyOf(filters);
            values = List.copyOf(values);
        }

        /** 一个设置框都没有。 */
        public static Boxes none() {
            return new Boxes(List.of(), List.of());
        }
    }

    /**
     * 一个过滤格。
     *
     * @param side         黄铜隧道这类每一面一格的，是哪一面；只有一格的为 null
     * @param label        Create 给这一格的名字
     * @param filter       现在放着的东西；空着是空物品
     * @param countVisible 限件数有没有显示出来（漏斗、智能溜槽这类能限"最多几件 / 正好几件"）
     * @param upTo         件数是"最多"（true）还是"正好"（false）
     * @param count        限的件数；0 是不限
     * @param maxCount     最多能限到几件（一组的大小）
     * @param spots        能点这一格的地方：点哪一面、点在哪（世界坐标）、离中心多远以内算点中
     */
    record FilterBox(int netId, Direction side, String label, ItemStack filter, boolean countVisible, boolean upTo,
            int count, int maxCount, List<Spot> spots) {
        public FilterBox {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(filter, "filter");
            spots = List.copyOf(spots);
        }
    }

    /** 过滤格能点的一处：方块哪一面上、中心在哪、多大半径以内算点中。 */
    record Spot(Direction face, Vec3 center, double radius) {
        public Spot {
            Objects.requireNonNull(face, "face");
            Objects.requireNonNull(center, "center");
        }
    }

    /** 数值框是哪一种：普通数、分正反转的转速、按刻计的时长、几选一的选项。 */
    enum Kind { NUMBER, SIGNED, TICKS, OPTION }

    /**
     * 一个数值或选项框。
     *
     * @param value   现在的值：数、带正负的转速、刻数，选项是第几个
     * @param max     设置界面里能拨到的最大数（转速是绝对值的上限，时长是每一档的上限）
     * @param options 选项的名字（枚举名，小写）；不是选项为空表
     */
    record ValueBox(int netId, Kind kind, String label, int value, int max, List<String> options) {
        public ValueBox {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(label, "label");
            options = List.copyOf(options);
        }
    }
}
