// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.maiwithu.maicraft.ability.design.api.DesignStore;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.ability.machine.spi.NetworkReader;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.permission.ReadsRememberedPlaces;
import org.maiwithu.maicraft.behavior.recipe.RecipeLookup;
import org.maiwithu.maicraft.behavior.travel.ReadsSeenTargets;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;

/**
 * 机器能力的入口：四个机器能力（看机器、审蓝图、改设置、用机器做东西）从这一处建出来登记。
 * 机器类型与网络读取器由联动登记表在进世界时建好交来；一个实例没装任何联动时照样登记，
 * 只是机器能力认不出任何一台机器，如实说不支持。
 */
public final class MachineAbilities {

    private MachineAbilities() {
    }

    /** 按清单顺序建四个机器能力：看机器、审蓝图、改设置、用机器做东西。 */
    public static List<AbilityModule> all(Supplier<PlayerContext> context, List<MachineType> machineTypes,
            List<NetworkReader> networkReaders, RecipeLookup recipes, ItemNeeds needs,
            BringsPlayerClose close, Interactions interactions, ReadsSeenTargets seenTargets,
            ReadsRememberedPlaces places, Optional<DesignStore> designs) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(recipes, "recipes");
        Objects.requireNonNull(needs, "needs");
        Objects.requireNonNull(close, "close");
        Objects.requireNonNull(interactions, "interactions");
        Objects.requireNonNull(seenTargets, "seenTargets");
        Objects.requireNonNull(places, "places");
        Objects.requireNonNull(designs, "designs");
        MachineServices services = new MachineServices(context, new LiveMachineWorld(context), machineTypes,
                networkReaders, new LiveMachineArea(context), recipes, needs, close,
                new LiveMachineInteractions(interactions), seenTargets, places);
        return List.of(new MachineInspectModule(services), new MachineReviewModule(services, designs),
                new MachineConfigureModule(services), new MachineRunModule(services));
    }
}
