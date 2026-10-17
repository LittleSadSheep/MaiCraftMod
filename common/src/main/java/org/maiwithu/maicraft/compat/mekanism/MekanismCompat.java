// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.mekanism;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.CompatRegistry;

/**
 * 通用机械（Mekanism）的联动入口。角色看机器的侧面配置、用配置器改侧面、看管道连成哪几张网、
 * 用线缆管道把机器接进网，要经这里碰模组；每一下都包一层，装的 Mekanism 和编译时用的对不上时整个联动停用。
 *
 * <p>交给登记表的：目录里每种机器与管道、兜住别的能配侧面机器的"其他 Mekanism 机器"、
 * 能量、流体、物品、化学品四种管网的网络读取器（化学品只读，不给接线的线材）。
 */
public final class MekanismCompat extends CompatModule {

    public static final String MOD_ID = MekanismMachineCatalog.MOD_ID;

    /** 别的能配侧面的 Mekanism 机器：看得出侧面配置、能改，说不出它是什么机器。 */
    static final MekanismMachineCatalog.Kind OTHER = new MekanismMachineCatalog.Kind(MOD_ID + ":other_machine",
            "其他 Mekanism 机器", MachineRole.PROCESSING, List.of());

    private final MekanismReads reads;

    public MekanismCompat(MekanismReads reads) {
        super(MOD_ID, "通用机械");
        this.reads = Objects.requireNonNull(reads, "reads");
    }

    @Override public void contribute(CompatRegistry registry) {
        for (MekanismMachineCatalog.Kind kind : MekanismMachineCatalog.kinds()) {
            registry.machineType(this, services -> {
                Set<Block> blocks = blocksOf(kind.blocks());
                return new MekanismMachineType(kind, this, services, state -> blocks.contains(state.getBlock()));
            });
        }
        registry.machineType(this, services -> new MekanismMachineType(OTHER, this, services, this::configurable));
        for (String medium : List.of("energy", "fluid", "item", "chemical")) {
            registry.networkReader(this, services -> new PipeNetworkReader(medium, this, services));
        }
        registry.knowledgeSource(this, new MekanismMachineKnowledge());
        // 机器的界面：认出哪几格是真的机器格，投料、开界面看、存取都认得它。
        registry.menuLayout(this, new MekanismMachineLayout(this));
    }

    /** 注册 ID 换成方块对象；注册表里没有的不算进来。 */
    static Set<Block> blocksOf(List<String> ids) {
        Set<Block> blocks = new HashSet<>();
        for (String id : ids) {
            ResourceLocation key = ResourceLocation.tryParse(id);
            if (key != null) BuiltInRegistries.BLOCK.getOptional(key).ifPresent(blocks::add);
        }
        return Set.copyOf(blocks);
    }

    /** 机器的侧面配置；见 {@link MekanismReads#sideConfig}。 */
    public Optional<MekanismReads.SideConfig> sideConfig(ClientLevel level, BlockPos at) {
        return call("读侧面配置", () -> reads.sideConfig(level, at));
    }

    /** 能不能配侧面；见 {@link MekanismReads#configurable}。 */
    public boolean configurable(BlockState state) {
        return call("认能配侧面的机器", () -> reads.configurable(state));
    }

    /** 管道读数；见 {@link MekanismReads#transmitter}。 */
    public Optional<MekanismReads.Transmitter> transmitter(ClientLevel level, BlockPos at) {
        return call("读管道连接", () -> reads.transmitter(level, at));
    }

    /** 配置器的模式；见 {@link MekanismReads#configuratorMode}。 */
    public Optional<String> configuratorMode(ItemStack stack) {
        return call("读配置器模式", () -> reads.configuratorMode(stack));
    }

    /** 此刻打开着的界面里真是机器物品格的格号；见 {@link MekanismReads#openMachineSlots}。 */
    public Set<Integer> openMachineSlots() {
        return call("认机器界面的物品格", reads::openMachineSlots);
    }

    /** 打开着的分拣器界面同步过来的样子；见 {@link MekanismReads#openSorter}。 */
    public Optional<MekanismReads.SorterState> openSorter(BlockPos at) {
        return call("读分拣器的规则", () -> reads.openSorter(at));
    }

    /** 按分拣器的"没规则对得上时往外送"开关；见 {@link MekanismReads#toggleSorterAutoEject}。 */
    public void toggleSorterAutoEject(BlockPos at) {
        run("按分拣器的往外送开关", () -> reads.toggleSorterAutoEject(at));
    }

    /** 让分拣器只认这一样物品；见 {@link MekanismReads#setSorterItemRule}。 */
    public void setSorterItemRule(BlockPos at, String itemId) {
        run("改分拣器的规则", () -> reads.setSorterItemRule(at, itemId));
    }

    /** 把主手配置器的模式往后拨一格；见 {@link MekanismReads#nextConfiguratorMode}。 */
    public void nextConfiguratorMode() {
        run("给配置器换模式", reads::nextConfiguratorMode);
    }
}
