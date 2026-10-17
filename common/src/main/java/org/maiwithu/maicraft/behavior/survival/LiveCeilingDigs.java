// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 换气时挖开压在水面上的方块的生产实现：那一格挖得动就给一份对准它的原生挖掘。淹水是马上要命的事，
 * 是别人的东西也挖，由换气任务在结果里写明；保护判断同时给挖三填一用（那里受保护的不挖）。
 */
public final class LiveCeilingDigs implements BreathTask.DigsCeiling {

    private final Supplier<BlockBreaking> diggings;
    private final Supplier<Protection> protection;

    public LiveCeilingDigs(Supplier<BlockBreaking> diggings, Supplier<Protection> protection) {
        this.diggings = Objects.requireNonNull(diggings, "diggings");
        this.protection = Objects.requireNonNull(protection, "protection");
    }

    @Override
    public Optional<BlockBreaking> dig(TickContext context, BlockPos ceiling) {
        ClientLevel level = context.player() == null ? null : context.player().level();
        if (level == null || level.getBlockState(ceiling).getDestroySpeed(level, ceiling) < 0) return Optional.empty();
        BlockBreaking digging = diggings.get();
        digging.aimAt(ceiling);
        return Optional.of(digging);
    }

    @Override
    public boolean belongsToSomeone(TickContext context, BlockPos ceiling) {
        ClientLevel level = context.player() == null ? null : context.player().level();
        return level != null && !mayDig(level, ceiling, protection.get());
    }

    /** 这一格挖不挖得：挖得动、而且不是别人的东西（保护判断拿不到时按受保护）。 */
    static boolean mayDig(ClientLevel level, BlockPos cell, Protection rules) {
        BlockState state = level.getBlockState(cell);
        if (state.getDestroySpeed(level, cell) < 0 || rules == null) return false;
        String dimension = level.dimension().location().toString();
        String type = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return !rules.blockProtected(new WorldPosition(cell.getX(), cell.getY(), cell.getZ(), dimension), type, Set.of());
    }
}
