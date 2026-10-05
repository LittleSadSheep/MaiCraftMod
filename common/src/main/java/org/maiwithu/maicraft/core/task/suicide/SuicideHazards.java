// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.suicide;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.combat.Menace;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;

/** 分刻观察已加载地形，找到能先走近、再踏入危险的位置；不挖坑、不造高塔，只有随身点火会在选定格留下原生火。 */
public final class SuicideHazards {
    // 原生点火被服务端拒绝或结果未知时登记这个键；之后不再换格反复点同一种火，交回其他候选或如实失败。
    static final String FIRE_REJECTED = "fire:native_rejection";
    private static final int FIRE_REACH = 8;
    public record Candidate(String method, BlockPos approach, BlockPos entry, int entityId, UUID entityUuid) {
        // 地形按进入危险的格子记尝试，生物按 UUID 记尝试；换一个接近站位不会自动重试同一入口。
        public String key() { return method + ":" + (entityUuid == null ? entry.asLong() : entityUuid); }

        public Vec3 destination(LocalPlayer player) {
            // 追怪持续读取同一只活体的位置，不把旧编号复用成另一只怪；这里并未再用搜索半径限制其移动。
            if (entityUuid == null) return Vec3.atBottomCenterOf(entry);
            var entity = player.level().getEntity(entityId);
            return entity != null && entity.isAlive() && entityUuid.equals(entity.getUUID()) ? entity.position() : null;
        }
    }

    private final Level level;
    private final BlockPos origin;
    private final SuicideRequest request;
    private final List<Candidate> terrain = new ArrayList<>();
    private int cursor;

    public SuicideHazards(LocalPlayer player, SuicideRequest request) {
        // 在规则和界面准备结束后的首次勘查固定起点；之后角色移动不会把地形搜索范围一同推向远处。
        this.level = player.level(); this.origin = player.blockPosition(); this.request = request;
    }

    public boolean scan() {
        // 每刻最多查看 1024 个脚位；候选站位同时受三维距离和上下十二格限制，避免一次大扫描卡住游戏。
        // auto 也先走完这轮地形扫描再选怪；已完成的扫描不重新开始，因此后来的新岩浆或新高台不会自动补入。
        int width = request.radius() * 2 + 1, volume = width * width * 25;
        if (!request.permits("lava") && !request.permits("fall")) { cursor = volume; return true; }
        for (int budget = 0; cursor < volume && budget < 1024; budget++, cursor++) {
            int x = cursor % width - request.radius(), z = cursor / width % width - request.radius();
            BlockPos stand = origin.offset(x, cursor / (width * width) - 12, z);
            if (stand.distSqr(origin) > request.radius() * request.radius() || !standing(level, stand)) continue;
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos entry = stand.relative(direction);
                if (!clear(level, entry) || !clear(level, entry.above())) continue;
                if (request.permits("lava") && lava(level, entry)) {
                    terrain.add(new Candidate("lava", stand, entry, -1, null));
                } else if (request.permits("fall") && fallHeight(level, entry) >= 6) {
                    terrain.add(new Candidate("fall", stand, entry, -1, null));
                }
            }
        }
        return cursor >= volume;
    }

    public Candidate choose(LocalPlayer player, Set<String> attempted) {
        var candidates = new ArrayList<>(terrain);
        // 怪物可能在扫描期间移动或消失，每次选择重新读取活体；不把玩家当作主动挑衅对象。
        if (request.permits("hostile")) {
            for (Mob mob : level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(request.radius()),
                    mob -> mob.isAlive() && Menace.threatens(mob, player))) {
                if (mob.blockPosition().distSqr(origin) <= request.radius() * request.radius())
                    candidates.add(new Candidate("hostile", mob.blockPosition(), mob.blockPosition(), mob.getId(), mob.getUUID()));
            }
        }
        // 这里只选当前最近且未放弃的候选，尚未证明它能走到；实际接近失败后才留下导航失败事实。
        var observed = nearest(player, candidates, attempted);
        // 已观察的岩浆、高处和怪物都用完或根本没有时，才改用随身打火石或火焰弹原地造火，避免身边没危险就直接失败。
        return observed != null || !request.permits("fire") || attempted.contains(FIRE_REJECTED) ? observed : ignition(player, attempted);
    }

    private Candidate ignition(LocalPlayer player, Set<String> attempted) {
        // 点火格围绕身体当前位置找最近处，上下两格、水平不超过 8 格且不越过请求半径；身上没有点火物就不编造候选。
        if (SuicideSelfHazard.igniterSlot(player.getInventory()) < 0) return null;
        int reach = Math.min(request.radius(), FIRE_REACH); BlockPos feet = player.blockPosition();
        var cells = new ArrayList<Candidate>();
        for (BlockPos cell : BlockPos.betweenClosed(feet.offset(-reach, -2, -reach), feet.offset(reach, 2, reach))) {
            BlockPos at = cell.immutable(); cells.add(new Candidate("fire", at, at, -1, null));
        }
        return nearest(player, cells, attempted);
    }

    private static Candidate nearest(LocalPlayer player, List<Candidate> candidates, Set<String> attempted) {
        return candidates.stream().filter(candidate -> !attempted.contains(candidate.key()))
                .filter(candidate -> valid(player, candidate))
                .min(Comparator.comparingDouble(candidate -> player.distanceToSqr(Vec3.atBottomCenterOf(candidate.approach()))))
                .orElse(null);
    }

    public static boolean valid(LocalPlayer player, Candidate candidate) {
        // 真正迈向危险前重查地形与实体身份；旧熔岩已凝固或旧怪物编号复用时不能继续盲走。
        if (candidate.entityUuid() != null) return candidate.destination(player) != null;
        Level level = player.level();
        // 点火格还要求格子本身为空气或已有火，并且身上仍有点火物或火已经烧着。
        if (candidate.method().equals("fire")) return igniteable(level, candidate.entry())
                && (burning(level, candidate.entry()) || SuicideSelfHazard.igniterSlot(player.getInventory()) >= 0);
        return standing(level, candidate.approach()) && clear(level, candidate.entry()) && clear(level, candidate.entry().above())
                && (candidate.method().equals("lava") ? lava(level, candidate.entry()) : fallHeight(level, candidate.entry()) >= 6);
    }

    private static boolean lava(Level level, BlockPos entry) {
        return level.isLoaded(entry.below()) && (level.getFluidState(entry).is(FluidTags.LAVA)
                || level.getFluidState(entry.below()).is(FluidTags.LAVA));
    }

    static boolean igniteable(Level level, BlockPos cell) {
        // 只在实心支撑面上方的空格点火；寻死不包含烧房子的授权，蔓延范围内有可燃方块或受保护格就换格。
        // 原生火向四周一格、向下一格、向上四格蔓延，这里读取同一范围，未加载格也按不可确认排除。
        if (!standing(level, cell) || !(level.getBlockState(cell).isAir() || burning(level, cell))
                || NavigationSafetyContext.protectsMutation(cell) || NavigationSafetyContext.protectsUse(cell.below())) return false;
        for (BlockPos nearby : BlockPos.betweenClosed(cell.offset(-1, -1, -1), cell.offset(1, 4, 1)))
            if (!level.isLoaded(nearby) || level.getBlockState(nearby).ignitedByLava()) return false;
        return true;
    }

    static boolean burning(Level level, BlockPos cell) {
        return level.isLoaded(cell) && level.getBlockState(cell).getBlock() instanceof BaseFireBlock;
    }

    static boolean standing(Level level, BlockPos feet) {
        return clear(level, feet) && clear(level, feet.above()) && level.isLoaded(feet.below())
                && level.getFluidState(feet).isEmpty()
                && level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP);
    }

    private static boolean clear(Level level, BlockPos cell) {
        return level.isInWorldBounds(cell) && level.isLoaded(cell) && !NavigationSafetyContext.forbidsBody(cell)
                && level.getBlockState(cell).getCollisionShape(level, cell).isEmpty();
    }

    static int fallHeight(Level level, BlockPos entry) {
        // 最多向下读 64 格，找到碰撞地面后才估计落差；任意流体、未加载格或禁止进入的格子都会排除该列。
        // 六格落差只是选候选的条件，不能证明护甲、药效或模组结算之后角色一定会受伤或死亡。
        for (int depth = 1; depth <= 64; depth++) {
            BlockPos below = entry.below(depth);
            if (!level.isInWorldBounds(below) || !level.isLoaded(below)
                    || NavigationSafetyContext.forbidsBody(below) || !level.getFluidState(below).isEmpty()) return 0;
            if (!level.getBlockState(below).getCollisionShape(level, below).isEmpty()) return depth - 1;
        }
        return 0;
    }
}
