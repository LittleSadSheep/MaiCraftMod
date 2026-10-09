// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsCreatureSituation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 别人的东西的生产实现：按交互与容器规格的取向分"改不改动世界"，归属交给全仓唯一的保护判断。
 *
 * <p>不改动世界的：开关门、活板门、栅栏门，按按钮、扳拉杆、敲钟、睡床，点开有界面的方块（箱子、熔炉、
 * 工作台这类，打开看看）；空手骑上船、矿车、马，空手找村民交易。其余一律算改动世界——倒流体、点火、
 * 耕地、剥树皮、剪毛、挤奶、喂食、染色、命名、拴绳、取下盔甲架上的东西，拿不准的也算。
 */
final class LiveOthersThings implements UseSeams.OthersThings {

    private final Supplier<PlayerContext> context;
    private final Supplier<Protection> protection;
    private final ReadsCreatureSituation creatures;

    LiveOthersThings(Supplier<PlayerContext> context, Supplier<Protection> protection,
            ReadsCreatureSituation creatures) {
        this.context = Objects.requireNonNull(context, "context");
        this.protection = Objects.requireNonNull(protection, "protection");
        this.creatures = Objects.requireNonNull(creatures, "creatures");
    }

    @Override
    public boolean changesWorld(String heldItem, ResolvedTarget target) {
        ClientLevel level = level();
        if (level == null) return true;
        if (target.isEntity()) {
            Entity entity = level.getEntity(target.entityId());
            // 空手骑上去、空手找村民交易不改动它；手里拿着东西对生物用（剪毛、挤奶、喂、染、拴、命名）都算。
            return heldItem != null || entity == null || !(entity instanceof Boat || entity instanceof AbstractMinecart
                    || entity instanceof AbstractHorse || entity instanceof AbstractVillager);
        }
        BlockState state = level.getBlockState(target.cell());
        Block block = state.getBlock();
        boolean operates = block instanceof DoorBlock || block instanceof TrapDoorBlock
                || block instanceof FenceGateBlock || block instanceof ButtonBlock || block instanceof LeverBlock
                || block instanceof BellBlock || block instanceof BedBlock;
        // 有界面的方块点开是打开看看（手里拿着方块也是先开界面）；开关门、按按钮这类是操作不是改动。
        return !(operates || state.getMenuProvider(level, target.cell()) != null);
    }

    @Override
    public boolean someoneElses(ResolvedTarget target, Set<String> protectedLandmarks) {
        Protection guard = protection.get();
        if (guard == null) return true;
        if (!target.isEntity()) {
            WorldPosition at = WorldPosition.here(target.cell().getX(), target.cell().getY(), target.cell().getZ());
            return guard.blockProtected(at, blockId(target), protectedLandmarks);
        }
        ClientLevel level = level();
        Entity entity = level == null ? null : level.getEntity(target.entityId());
        if (entity == null) return true;
        // 处境读不到按别人的算；玩家不是谁的东西，但对玩家用东西同样要点名。
        return creatures.situationOf(entity.getUUID())
                .map(situation -> situation.player() || guard.creatureProtected(situation))
                .orElse(true);
    }

    private static String blockId(ResolvedTarget target) {
        ResourceLocation id = ResourceLocation.tryParse(target.typeId());
        return id != null && BuiltInRegistries.BLOCK.containsKey(id) ? target.typeId() : null;
    }

    private ClientLevel level() {
        PlayerContext current = context.get();
        return current == null ? null : current.level();
    }
}
