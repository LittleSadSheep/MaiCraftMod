// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 挖楼梯读的现场：角色站在哪、脸朝哪、一格是什么样，都读当刻的客户端世界；
 * 走下一级交给走到，只走不改地形（这一级已经挖通了，不该再挖别的、垫别的）。
 */
public final class LiveStairsSite implements ClientStairsDown.Site {

    private final WalkTo walks;

    public LiveStairsSite(WalkTo walks) {
        this.walks = Objects.requireNonNull(walks, "walks");
    }

    @Override
    public Optional<BlockPos> feet(TickContext context) {
        LocalPlayer self = self(context);
        return self == null ? Optional.empty() : Optional.of(self.blockPosition());
    }

    @Override
    public boolean onGround(TickContext context) {
        LocalPlayer self = self(context);
        return self != null && self.onGround();
    }

    @Override
    public Direction facing(TickContext context) {
        LocalPlayer self = self(context);
        return self == null ? Direction.NORTH : self.getDirection();
    }

    @Override
    public ClientStairsDown.Ground ground(TickContext context, BlockPos cell) {
        ClientLevel level = level(context);
        // 没加载、看不到的格子不当成空的：按挖不动处理，楼梯停在这里。
        if (level == null || !level.hasChunkAt(cell)) return ClientStairsDown.Ground.UNBREAKABLE;
        BlockState state = level.getBlockState(cell);
        if (!state.getFluidState().isEmpty()) return ClientStairsDown.Ground.FLUID;
        if (state.getCollisionShape(level, cell).isEmpty()) return ClientStairsDown.Ground.OPEN;
        if (state.getDestroySpeed(level, cell) < 0) return ClientStairsDown.Ground.UNBREAKABLE;
        return ClientStairsDown.Ground.DIGGABLE;
    }

    @Override
    public String blockType(TickContext context, BlockPos cell) {
        ClientLevel level = level(context);
        return level == null ? "minecraft:air"
                : BuiltInRegistries.BLOCK.getKey(level.getBlockState(cell).getBlock()).toString();
    }

    @Override
    public String dimension(TickContext context) {
        ClientLevel level = level(context);
        return level == null ? null : level.dimension().location().toString();
    }

    @Override
    public Action stepDownTo(BlockPos feet) {
        return walks.start(GoalCompiler.standOn(feet), TerrainPermit.WALK_ONLY);
    }

    private static LocalPlayer self(TickContext context) {
        return context.player() == null ? null : context.player().localPlayer();
    }

    private static ClientLevel level(TickContext context) {
        return context.player() == null ? null : context.player().level();
    }
}
