// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.CompatRegistry;

/**
 * 机械动力交给登记表的东西：目录里每一种机器、兜住附属模组动力方块的两种"其他"、应力网的网络读取器。
 * 机器类型要用进了世界才有的玩家行为，所以交的是建法；建的时候按注册 ID 找到方块，认方块只比方块对象，
 * 扫一片几万格时不用每格查注册表。
 */
final class CreateMachines {

    /** 附属模组里自己发出转动的方块：看得出它转不转、在哪张网上，别的说不出。 */
    static final CreateMachineCatalog.Kind OTHER_SOURCE = new CreateMachineCatalog.Kind(
            CreateMachineCatalog.MOD_ID + ":other_power_source", "其他动力源（附属模组的）",
            MachineRole.POWER_SOURCE, CreateMachineCatalog.Work.TURNING, List.of());
    /** 附属模组里会转、会传动力的方块。 */
    static final CreateMachineCatalog.Kind OTHER_KINETIC = new CreateMachineCatalog.Kind(
            CreateMachineCatalog.MOD_ID + ":other_kinetic", "其他动力方块（附属模组的）",
            MachineRole.TRANSMISSION, CreateMachineCatalog.Work.TURNING, List.of());

    private CreateMachines() {
    }

    /** 把机器类型与网络读取器的建法交给登记表；目录在前、兜底的在后，同一格先被目录认领。 */
    static void contribute(CreateCompat compat, CompatRegistry registry) {
        for (CreateMachineCatalog.Kind kind : CreateMachineCatalog.kinds()) {
            registry.machineType(compat, services -> listed(kind, compat, services));
        }
        registry.machineType(compat, services -> new CreateMachineType(OTHER_SOURCE, compat, services,
                state -> compat.kinetic(state) && compat.generator(state), new StationHandling(compat, services),
                Optional.empty()));
        registry.machineType(compat, services -> new CreateMachineType(OTHER_KINETIC, compat, services,
                state -> compat.kinetic(state) && !compat.generator(state), new StationHandling(compat, services),
                Optional.empty()));
        registry.networkReader(compat, services -> new KineticNetworkReader(compat, services));
    }

    // 目录里的一种：按注册 ID 找方块（装的版本里没有的跳过），传送带与链条输送机带上各自的安装段。
    private static CreateMachineType listed(CreateMachineCatalog.Kind kind, CreateCompat compat,
            PlayerServices services) {
        Set<Block> blocks = blocksOf(kind.blocks());
        Optional<CreateInstallation> installation = switch (kind.work()) {
            case BELT -> Optional.of(new BeltInstallation(compat, services));
            case CHAIN_CONVEYOR -> Optional.of(new ChainConveyorInstallation(compat, services));
            case CRAFTER -> Optional.of(new CrafterLinkInstallation(compat, services));
            case ARM -> Optional.of(new ArmInstallation(compat, services));
            // 装置都会粘强力胶：谁先接下这一段谁来装，粘的是哪一片由安装段自己说。
            case MOTION -> Optional.of(new GlueInstallation(compat, services));
            // 铁路方块都会铺轨：两端的轨道放好后手持轨道点起点、点终点。
            case RAIL -> Optional.of(new TrackInstallation(compat, services));
            default -> Optional.empty();
        };
        return new CreateMachineType(kind, compat, services, state -> blocks.contains(state.getBlock()),
                new StationHandling(compat, services), installation);
    }

    /** 注册 ID 换成方块对象；注册表里没有的（版本里去掉了、拼错了）不算进来。 */
    static Set<Block> blocksOf(List<String> ids) {
        Set<Block> blocks = new HashSet<>();
        for (String id : ids) {
            ResourceLocation key = ResourceLocation.tryParse(id);
            if (key != null) BuiltInRegistries.BLOCK.getOptional(key).ifPresent(blocks::add);
        }
        return Set.copyOf(blocks);
    }
}
