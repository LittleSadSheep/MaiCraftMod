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
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.combat.Menace;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;

/** 分刻观察已加载地形，找到能先走近、再踏入危险的位置；不挖坑、不造高塔，也不修改服务器世界。 */
public final class SuicideHazards {
    public record Candidate(String method, BlockPos approach, BlockPos entry, int entityId, UUID entityUuid) {
        public String key() { return method + ":" + (entityUuid == null ? entry.asLong() : entityUuid); }

        public Vec3 destination(LocalPlayer player) {
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
        this.level = player.level(); this.origin = player.blockPosition(); this.request = request;
    }

    public boolean scan() {
        // 每刻最多查看 1024 个脚位；只检查起点上下十二格和声明半径，避免一次大扫描卡住游戏。
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
        return candidates.stream().filter(candidate -> !attempted.contains(candidate.key()))
                .filter(candidate -> valid(player, candidate))
                .min(Comparator.comparingDouble(candidate -> player.distanceToSqr(Vec3.atBottomCenterOf(candidate.approach()))))
                .orElse(null);
    }

    public static boolean valid(LocalPlayer player, Candidate candidate) {
        // 真正迈向危险前重查地形与实体身份；旧熔岩已凝固或旧怪物编号复用时不能继续盲走。
        if (candidate.entityUuid() != null) return candidate.destination(player) != null;
        Level level = player.level();
        return standing(level, candidate.approach()) && clear(level, candidate.entry()) && clear(level, candidate.entry().above())
                && (candidate.method().equals("lava") ? lava(level, candidate.entry()) : fallHeight(level, candidate.entry()) >= 6);
    }

    private static boolean lava(Level level, BlockPos entry) {
        return level.isLoaded(entry.below()) && (level.getFluidState(entry).is(FluidTags.LAVA)
                || level.getFluidState(entry.below()).is(FluidTags.LAVA));
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
        // 沿实际下落柱寻找地面；水、未加载空间和禁止进入区域不作为已观察到的坠落伤害位置。
        for (int depth = 1; depth <= 64; depth++) {
            BlockPos below = entry.below(depth);
            if (!level.isInWorldBounds(below) || !level.isLoaded(below)
                    || NavigationSafetyContext.forbidsBody(below) || !level.getFluidState(below).isEmpty()) return 0;
            if (!level.getBlockState(below).getCollisionShape(level, below).isEmpty()) return depth - 1;
        }
        return 0;
    }
}
