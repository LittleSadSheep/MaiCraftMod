// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.suicide;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.behavior.navigation.util.BlockPassability;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.game.player.FallDamage;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.Hostility;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 寻死看地形与敌对生物的读端：每次都读当刻的客户端世界。没加载的格子一律答"不知道"那一边：
 * 不能站、进不去、不是岩浆；受保护与放方块的许可交给保护判断与许可检查点，全仓同一份。
 */
final class LiveSuicideWorld implements SuicideWorld {

    private final Supplier<PlayerContext> context;
    private final Protection protection;
    private final PermissionCheck permission;
    private final Permissions permissions;

    /** @param permissions 这次寻死目标的许可：放火、倒岩浆按它过放方块的检查 */
    LiveSuicideWorld(Supplier<PlayerContext> context, Protection protection, PermissionCheck permission,
            Permissions permissions) {
        this.context = Objects.requireNonNull(context, "context");
        this.protection = Objects.requireNonNull(protection, "protection");
        this.permission = Objects.requireNonNull(permission, "permission");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
    }

    private ClientLevel level() {
        PlayerContext current = context.get();
        return current == null ? null : current.level();
    }

    // 客户端世界的 hasChunkAt 永远答"加载着"，按区块真加载没加载判。
    @Override public boolean loaded(BlockPos cell) {
        ClientLevel level = level();
        return level != null && level.isLoaded(cell);
    }

    @Override public boolean belowWorld(BlockPos cell) {
        ClientLevel level = level();
        return level != null && cell.getY() < level.getMinBuildHeight();
    }

    @Override public boolean standable(BlockPos feet) {
        ClientLevel level = level();
        return level != null && level.isLoaded(feet.below()) && level.isLoaded(feet.above())
                && BlockPassability.isDryStandable(level, feet);
    }

    // 进得去、往下掉也不会被接住：会接住下落的（梯子、蜘蛛网这类）不算。
    @Override public boolean passable(BlockPos cell) {
        BlockState state = state(cell);
        return state != null && state.getCollisionShape(level(), cell).isEmpty() && !breaksFall(cell);
    }

    // 类别 F：能攀的方块清掉下落距离，蜘蛛网、细雪让人慢慢落，黏液块把人弹起来，落上去都不摔伤。
    @Override public boolean breaksFall(BlockPos cell) {
        BlockState state = state(cell);
        return state != null && (state.is(BlockTags.CLIMBABLE) || state.is(Blocks.COBWEB)
                || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.SLIME_BLOCK));
    }

    @Override public boolean fluid(BlockPos cell) {
        return loaded(cell) && !level().getFluidState(cell).isEmpty();
    }

    @Override public boolean lava(BlockPos cell) {
        return loaded(cell) && level().getFluidState(cell).is(FluidTags.LAVA);
    }

    @Override public boolean cactus(BlockPos cell) {
        BlockState state = state(cell);
        return state != null && state.is(Blocks.CACTUS);
    }

    @Override public boolean air(BlockPos cell) {
        BlockState state = state(cell);
        return state != null && state.isAir();
    }

    @Override public boolean sturdyTop(BlockPos cell) {
        BlockState state = state(cell);
        return state != null && state.isFaceSturdy(level(), cell, Direction.UP);
    }

    @Override public boolean fireAt(BlockPos cell) {
        BlockState state = state(cell);
        return state != null && state.getBlock() instanceof BaseFireBlock;
    }

    @Override public boolean flammable(BlockPos cell) {
        BlockState state = state(cell);
        return state != null && state.ignitedByLava();
    }

    @Override public boolean protectedBlock(BlockPos cell) {
        BlockState state = state(cell);
        if (state == null || state.isAir()) return false;
        String blockType = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return protection.blockProtected(WorldPosition.here(cell.getX(), cell.getY(), cell.getZ()), blockType,
                permissions.protectedLandmarks());
    }

    @Override public boolean mayPlaceAt(BlockPos cell) {
        return permission.blockAllowed(permissions, PermissionCheck.WorldAction.PLACE_BLOCK,
                WorldPosition.here(cell.getX(), cell.getY(), cell.getZ()), "minecraft:fire").isEmpty();
    }

    @Override public boolean hotDimension() {
        ClientLevel level = level();
        return level != null && level.dimensionType().ultraWarm();
    }

    @Override public FallDamage.Landing landingOn(BlockPos support) {
        BlockState state = state(support);
        return state == null ? FallDamage.Landing.ORDINARY : FallDamage.Landing.of(state);
    }

    // 敌对生物按"此刻会不会主动打人"认：玩家、没惹毛的中立生物不在里面；按到起点的距离筛。
    @Override public List<HostileMob> hostileMobs(BlockPos origin, int radius) {
        ClientLevel level = level();
        if (level == null) return List.of();
        Vec3 center = Vec3.atBottomCenterOf(origin);
        List<HostileMob> mobs = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (!entity.isAlive() || !Hostility.hostileNow(entity)) continue;
            if (entity.position().distanceToSqr(center) > (double) radius * radius) continue;
            mobs.add(new HostileMob(entity.getId(), typeOf(entity), entity.position()));
        }
        return mobs;
    }

    // 编号会被后来的实体复用：种类对不上、死了、不再会打人（中立的消了气）都当它没了。
    @Override public Optional<Vec3> mobPosition(int id, String type) {
        ClientLevel level = level();
        Entity entity = level == null ? null : level.getEntity(id);
        if (entity == null || !entity.isAlive() || !typeOf(entity).equals(type) || !Hostility.hostileNow(entity)) {
            return Optional.empty();
        }
        return Optional.of(entity.position());
    }

    private static String typeOf(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }

    private BlockState state(BlockPos cell) {
        ClientLevel level = level();
        return level == null || !level.isLoaded(cell) ? null : level.getBlockState(cell);
    }
}
