// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.mekanism;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 读通用机械（Mekanism）的模组读写接缝：机器每一面的侧面配置（哪种介质、进还是出），管道往哪几面连着别的管道、
 * 往哪几面连着机器，手里配置器现在是什么模式；还有一下写：像按换模式键那样把手里配置器的模式往后拨一格。
 * 侧面配置与管道连接都是 Mekanism 同步给客户端画出来的。只用 Minecraft 与 Java 的类型；读写端在 NeoForge 模块里。
 */
public interface MekanismReads {

    /** 这一格机器的侧面配置；不是能配侧面的 Mekanism 机器、没加载给空。 */
    Optional<SideConfig> sideConfig(ClientLevel level, BlockPos at);

    /** 这种方块是不是能配侧面的 Mekanism 机器。 */
    boolean configurable(BlockState state);

    /** 这一格管道的读数；不是 Mekanism 的管道（线缆、管道、导管）、没加载给空。 */
    Optional<Transmitter> transmitter(ClientLevel level, BlockPos at);

    /** 这件物品是配置器时它现在的模式（configurate_items、configurate_energy……）；不是配置器给空。 */
    Optional<String> configuratorMode(ItemStack stack);

    /** 把主手配置器的模式往后拨一格，和按一下换模式键一样；服务器改好后物品上的模式跟着变。 */
    void nextConfiguratorMode();

    /**
     * 此刻打开着的界面里，背后真是 Mekanism 机器物品格的那些格号（输入、输出、电池、附加格）；
     * 升级、配方显示这类虚拟格与只看不存的格不算。没打开界面或不是 Mekanism 的界面给空集。
     */
    Set<Integer> openMachineSlots();

    /**
     * 此刻打开着的界面是 at 这台物流分拣器的界面时，界面同步过来的样子：没有规则对得上时往不往外送、每条规则；
     * 不是它的界面给空。分拣器的规则只在开着界面时同步给客户端。
     */
    Optional<SorterState> openSorter(BlockPos at);

    /** 在打开着的 at 这台分拣器界面上按一下"没规则对得上时往外送"的开关（Mekanism 自己的界面交互包）。 */
    void toggleSorterAutoEject(BlockPos at);

    /**
     * 让打开着的 at 这台分拣器只认这一样物品：没有规则时新建一条精确的单物品规则，
     * 只有一条同物品规则时把它改成精确的（Mekanism 自己的新建、修改规则包）；别的情况什么都不发。
     */
    void setSorterItemRule(BlockPos at, String itemId);

    /** 分拣器界面里的样子：没规则对得上时往不往外送，每条规则按界面里的顺序。 */
    record SorterState(boolean autoEject, List<SorterRule> rules) {

        public SorterState {
            rules = List.copyOf(rules);
        }
    }

    /**
     * 一条规则：是不是单物品规则、认的物品（不是单物品规则为空串）、是不是精确的
     * （开着、不模糊匹配、不兼作默认、不按数量、不带颜色）。
     */
    record SorterRule(boolean itemRule, String itemId, boolean exact) {

        public SorterRule {
            Objects.requireNonNull(itemId, "itemId");
        }
    }

    /**
     * 一台机器的侧面配置。
     *
     * @param facing  机器朝向（侧面配置按相对朝向存，这里已经换成世界方向）
     * @param sides   每种介质（item、fluid、chemical、energy、heat）每个世界方向现在是什么（none、input、output……）
     * @param choices 每种介质能选哪几种
     */
    record SideConfig(Direction facing, Map<String, Map<Direction, String>> sides, Map<String, List<String>> choices) {
        public SideConfig {
            Objects.requireNonNull(facing, "facing");
            sides = Map.copyOf(sides);
            choices = Map.copyOf(choices);
        }
    }

    /**
     * 一格管道。
     *
     * @param media     传的是什么（energy、fluid、item、chemical、heat，可以不止一种）
     * @param pipes     往哪几面连着别的管道
     * @param machines  往哪几面连着机器（能收能出的那一面）
     */
    record Transmitter(Set<String> media, Set<Direction> pipes, Set<Direction> machines) {
        public Transmitter {
            media = Set.copyOf(media);
            pipes = Set.copyOf(pipes);
            machines = Set.copyOf(machines);
        }
    }
}
