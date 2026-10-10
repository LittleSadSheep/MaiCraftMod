// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import org.maiwithu.maicraft.ability.design.api.DesignStore;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.ability.machine.spi.NetworkReader;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.construction.AnchorResolver;
import org.maiwithu.maicraft.behavior.construction.ConstructionServices;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.permission.ReadsRememberedPlaces;
import org.maiwithu.maicraft.behavior.recipe.RecipeLookup;
import org.maiwithu.maicraft.behavior.travel.ReadsSeenTargets;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;

/**
 * 机器能力的入口：机器能力（看机器、审蓝图、改设置、用机器做东西、按蓝图施工）
 * 从这一处建出来登记。机器类型与网络读取器由联动登记表在进世界时建好交来；一个实例没装任何联动时
 * 照样登记，机器能力认不出任何一台机器就如实说不支持，施工放方块不需要认识它。
 */
public final class MachineAbilities {

    private MachineAbilities() {
    }

    /**
     * 按清单顺序建六个机器能力：看机器、审蓝图、改设置、用机器做东西、按蓝图施工、接网络。
     * 机器档案按世界存在给定的文档库里，键是这个世界的身份编号。
     */
    public static List<AbilityModule> all(Supplier<PlayerContext> context, List<MachineType> machineTypes,
            List<NetworkReader> networkReaders, RecipeLookup recipes, ItemNeeds needs,
            BringsPlayerClose close, Interactions interactions, ReadsSeenTargets seenTargets,
            ReadsRememberedPlaces places, WorldMemory memory, DocumentStore worldDocuments, String worldKey,
            AnchorResolver anchors, Optional<DesignStore> designs,
            Function<Permissions, ConstructionServices> construction) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(recipes, "recipes");
        Objects.requireNonNull(needs, "needs");
        Objects.requireNonNull(close, "close");
        Objects.requireNonNull(interactions, "interactions");
        Objects.requireNonNull(seenTargets, "seenTargets");
        Objects.requireNonNull(places, "places");
        Objects.requireNonNull(memory, "memory");
        Objects.requireNonNull(worldDocuments, "worldDocuments");
        Objects.requireNonNull(worldKey, "worldKey");
        Objects.requireNonNull(anchors, "anchors");
        Objects.requireNonNull(designs, "designs");
        Objects.requireNonNull(construction, "construction");
        MachineServices services = new MachineServices(context, new LiveMachineWorld(context), machineTypes,
                networkReaders, new LiveMachineArea(context), recipes, needs, close,
                new LiveMachineInteractions(interactions), seenTargets, places);
        MachineArchives archives = new MachineArchives(worldDocuments, worldKey);
        return List.of(new MachineInspectModule(services), new MachineReviewModule(services, designs),
                new MachineConfigureModule(services), new MachineRunModule(services),
                new MachineBuildModule(services, archives, anchors, designs, memory, construction));
    }
}
