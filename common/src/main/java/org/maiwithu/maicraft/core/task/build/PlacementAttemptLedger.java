package org.maiwithu.maicraft.core.task.build;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.build.BuildingBudgets;

/**
 * 按目标格记住失败的点击方式和整个失败站位，避免下一次又原样重试。
 * 点击方式包括脚下格、被点击的格和面、精确点击点、是否蹲下；少量微调仍失败就换站位，不能靠换点清空预算。
 */
final class PlacementAttemptLedger {
    private record Key(BlockPos stance, BlockPos clicked, Direction face, Vec3 point, boolean sneak) {
        static Key of(BuildPlacementGeometry.Gesture gesture) {
            return new Key(gesture.stance(), gesture.clicked(), gesture.face(), gesture.point(), gesture.sneak());
        }
    }
    private final Map<Long, Set<Key>> rejected = new HashMap<>();
    private final Map<Long, Set<BlockPos>> stances = new HashMap<>();
    private final Map<Long, Map<BlockPos, Integer>> stanceFailures = new HashMap<>();
    private final int maxFailuresPerStance;

    PlacementAttemptLedger() { this(BuildingBudgets.current().maxPlacementFailuresPerStance()); }
    PlacementAttemptLedger(int maxFailuresPerStance) {
        if (maxFailuresPerStance < 1) throw new IllegalArgumentException("Placement stance failure budget must be positive");
        this.maxFailuresPerStance = maxFailuresPerStance;
    }

    // 任何一项命中失败记录就拒绝；整站位被拒绝时，该站位的其他点击方式也一起被排除。
    boolean allows(BuildTaskRecord.Target target, BuildPlacementGeometry.Gesture gesture) {
        long key = target.pos().asLong();
        return !rejected.getOrDefault(key, Set.of()).contains(Key.of(gesture))
                && !stances.getOrDefault(key, Set.of()).contains(gesture.stance());
    }

    void reject(BuildTaskRecord.Target target, BuildPlacementGeometry.Gesture gesture) {
        if (gesture == null) return;
        long key = target.pos().asLong();
        rejected.computeIfAbsent(key, ignored -> new HashSet<>()).add(Key.of(gesture));
        // 真实准心或原生回执已否定这次放法，累计整处脚位的预算；微小转角、换面和站内挪动都不能让角色无限重试。
        int failures = stanceFailures.computeIfAbsent(key, ignored -> new HashMap<>()).merge(
                gesture.stance(), 1, (previous, one) -> previous >= maxFailuresPerStance ? maxFailuresPerStance : previous + one);
        if (failures >= maxFailuresPerStance) rejectStance(target, gesture.stance());
    }

    void rejectStance(BuildTaskRecord.Target target, BlockPos stance) {
        stances.computeIfAbsent(target.pos().asLong(), ignored -> new HashSet<>()).add(stance.immutable());
    }

    // 这里只数被拒绝的点击方式，不包含单独拒绝的整站位数量。
    int rejectedCount(BuildTaskRecord.Target target) {
        return rejected.getOrDefault(target.pos().asLong(), Set.of()).size();
    }

    int stanceFailureCount(BuildTaskRecord.Target target, BlockPos stance) {
        return stanceFailures.getOrDefault(target.pos().asLong(), Map.of()).getOrDefault(stance, 0);
    }
    int rejectedStanceCount(BuildTaskRecord.Target target) { return stances.getOrDefault(target.pos().asLong(), Set.of()).size(); }
    int maxFailuresPerStance() { return maxFailuresPerStance; }

    /**
     * 附近六格内发生已确认施工变化后，清掉相关目标的失败记录，让新露出的支撑面或路线有机会再试。
     */
    void changedNear(BlockPos changed) {
        rejected.keySet().removeIf(key -> BlockPos.of(key).distSqr(changed) <= 36);
        stances.keySet().removeIf(key -> BlockPos.of(key).distSqr(changed) <= 36);
        stanceFailures.keySet().removeIf(key -> BlockPos.of(key).distSqr(changed) <= 36);
    }

    void complete(BuildTaskRecord.Target target) {
        // 目标已经实物完成，其站位历史一起清理；别的未完成目标仍保留各自的失败预算。
        rejected.remove(target.pos().asLong()); stances.remove(target.pos().asLong()); stanceFailures.remove(target.pos().asLong());
    }
}
