// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 生物处境的读端：从客户端世界里把一只生物是玩家吗、敌对吗、有名字吗、被驯服了吗、
 * 拴着绳吗、在圈养设施里吗逐项读出来。
 *
 * <p>"在围栏里"是客户端能看到的粗看法：生物四周与脚下的小范围里有没有栅栏、墙、
 * 栏门这类圈养方块（见 {@link #enclosedBy}）。它回答的是"这像是别人圈的牲畜"，
 * 不是精确的圈养判定；认不出处境时整条返回空，调用方按受保护处理。
 */
public final class ClientCreatureSituations implements ReadsCreatureSituation {

    // 圈养扫描范围：以生物为中心的水平半径 3 格、脚下到头顶上 2 格，够认出常见的栏圈。
    private static final int ENCLOSURE_RADIUS = 3;

    private final Supplier<PlayerContext> context;

    public ClientCreatureSituations(Supplier<PlayerContext> context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public Optional<CreatureSituation> situationOf(UUID entityId) {
        PlayerContext current = context.get();
        if (current == null || current.localPlayer() == null || current.level() == null) {
            return Optional.empty();
        }
        LocalPlayer self = current.localPlayer();
        ClientLevel level = current.level();
        Entity found = null;
        for (Entity entity : level.entitiesForRendering()) {
            if (entityId.equals(entity.getUUID())) {
                found = entity;
                break;
            }
        }
        if (found == null || found.isRemoved()) {
            return Optional.empty();
        }
        return Optional.of(new CreatureSituation(
                found instanceof Player && found != self,
                found instanceof Enemy,
                found.hasCustomName(),
                found instanceof OwnableEntity ownable && ownable.getOwnerUUID() != null,
                found instanceof Leashable leashable && leashable.isLeashed(),
                enclosedBy(level::getBlockState, found.blockPosition())));
    }

    /**
     * 生物周围的小范围内有没有圈养方块：栅栏、墙、栏门，脚下踩着的也算。
     * 圈住半边也算——半圈里的牲畜多半还是别人家的，拿不准时宁可不碰。
     * 纯函数，离线测试给一个按坐标回方块状态的查找函数。
     */
    static boolean enclosedBy(Function<BlockPos, BlockState> stateAt, BlockPos center) {
        BlockPos from = center.offset(-ENCLOSURE_RADIUS, -1, -ENCLOSURE_RADIUS);
        BlockPos to = center.offset(ENCLOSURE_RADIUS, 2, ENCLOSURE_RADIUS);
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
            BlockState state = stateAt.apply(pos.immutable());
            if (state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS)
                    || state.is(BlockTags.FENCE_GATES)) {
                return true;
            }
        }
        return false;
    }
}
