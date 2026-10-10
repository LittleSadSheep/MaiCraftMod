// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.ability.machine.spi.NetworkReader;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.permission.ReadsRememberedPlaces;
import org.maiwithu.maicraft.behavior.recipe.RecipeLookup;
import org.maiwithu.maicraft.behavior.travel.ReadsSeenTargets;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 机器能力共用的服务束：登记表建出来的机器类型与网络读取器、分刻读一片格子的读端、
 * 配方查询、缺东西去拿、靠近与交互，以及把目标对象落实成格子要问的现场与编号。
 * 启动时建好一份，四个机器能力共用；测试里整束换成替身。
 *
 * @param context        当刻的角色；不在世界里为 null，每刻取一次，不留到下一刻（分刻扫描要用）
 * @param world          角色在哪、一格是什么的最小只读视图
 * @param machineTypes   登记了的机器类型，按联动清单顺序；一格归最先认领它的那种
 * @param networkReaders 登记了的网络读取器
 * @param area           分刻读一片范围内格子的读端
 * @param recipes        配方查询：machine_run 选工序、machine_review 查工序都问它
 * @param needs          缺东西时去拿：投料前把料备齐、配置器不在身上时去拿
 * @param close          靠近：拨开关前先走到够得着的位置
 * @param interactions   原生交互：开关是机器自带的拉杆或按钮时右键拨一下
 * @param seenTargets    观察编号查位置；b# 指的机器组成格靠它落实
 * @param places         按名字查记过的地点；档案还没有接入，地标先按记过的地点找
 */
record MachineServices(Supplier<PlayerContext> context, MachineWorldView world, List<MachineType> machineTypes,
                       List<NetworkReader> networkReaders, ReadsMachineArea area, RecipeLookup recipes,
                       ItemNeeds needs, BringsPlayerClose close, MachineInteractions interactions,
                       ReadsSeenTargets seenTargets, ReadsRememberedPlaces places) {

    MachineServices {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(world, "world");
        machineTypes = List.copyOf(machineTypes);
        networkReaders = List.copyOf(networkReaders);
        Objects.requireNonNull(area, "area");
        Objects.requireNonNull(recipes, "recipes");
        Objects.requireNonNull(seenTargets, "seenTargets");
        Objects.requireNonNull(places, "places");
    }

    /** 认领一格的机器类型：按登记顺序问，最先认领的算数；都没有为 null。 */
    MachineType claiming(BlockState state) {
        for (MachineType type : machineTypes) {
            if (type.covers(state)) {
                return type;
            }
        }
        return null;
    }

    /** 认不出这格是什么机器时的问题：写明方块 ID 与"缺哪个联动"的说法。 */
    Problem unclaimedHere(BlockState state, String what) {
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return Problem.of(Problem.Kind.UNSUPPORTED,
                "这格是 " + blockId + "，没有机器类型认领它"
                        + (machineTypes.isEmpty() ? "（这个实例没有登记任何机器联动）" : "，" + what + "做不了"),
                "装了对应模组的联动后才能对它" + what);
    }
}
