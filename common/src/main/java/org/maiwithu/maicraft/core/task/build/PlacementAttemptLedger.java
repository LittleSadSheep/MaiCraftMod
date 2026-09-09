package org.maiwithu.maicraft.core.task.build;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** Rejections belong to a target and physical worksite, including current-position shortcuts. */
final class PlacementAttemptLedger {
    private record Key(BlockPos stance, BlockPos clicked, Direction face, Vec3 point, boolean sneak) {
        static Key of(BuildPlacementGeometry.Gesture gesture) {
            return new Key(gesture.stance(), gesture.clicked(), gesture.face(), gesture.point(), gesture.sneak());
        }
    }
    private final Map<Long, Set<Key>> rejected = new HashMap<>();
    private final Map<Long, Set<BlockPos>> stances = new HashMap<>();

    boolean allows(BuildTaskRecord.Target target, BuildPlacementGeometry.Gesture gesture) {
        long key = target.pos().asLong();
        return !rejected.getOrDefault(key, Set.of()).contains(Key.of(gesture))
                && !stances.getOrDefault(key, Set.of()).contains(gesture.stance());
    }

    void reject(BuildTaskRecord.Target target, BuildPlacementGeometry.Gesture gesture) {
        if (gesture != null) rejected.computeIfAbsent(target.pos().asLong(), ignored -> new HashSet<>()).add(Key.of(gesture));
    }

    void rejectStance(BuildTaskRecord.Target target, BlockPos stance) {
        stances.computeIfAbsent(target.pos().asLong(), ignored -> new HashSet<>()).add(stance.immutable());
    }

    int rejectedCount(BuildTaskRecord.Target target) {
        return rejected.getOrDefault(target.pos().asLong(), Set.of()).size();
    }

    /** Nearby confirmed construction changes can open a ray, foothold or click support. */
    void changedNear(BlockPos changed) {
        rejected.keySet().removeIf(key -> BlockPos.of(key).distSqr(changed) <= 36);
        stances.keySet().removeIf(key -> BlockPos.of(key).distSqr(changed) <= 36);
    }

    void complete(BuildTaskRecord.Target target) {
        rejected.remove(target.pos().asLong()); stances.remove(target.pos().asLong());
    }
}
