package org.maiwithu.maicraft.core.task.build;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * 按目标格记住失败的点击方式和整个失败站位，避免下一次又原样重试。
 * 点击方式包括脚下格、被点击的格和面、精确点击点、是否蹲下；不同点击点可以单独尝试。
 */
final class PlacementAttemptLedger {
    private record Key(BlockPos stance, BlockPos clicked, Direction face, Vec3 point, boolean sneak) {
        static Key of(BuildPlacementGeometry.Gesture gesture) {
            return new Key(gesture.stance(), gesture.clicked(), gesture.face(), gesture.point(), gesture.sneak());
        }
    }
    private final Map<Long, Set<Key>> rejected = new HashMap<>();
    private final Map<Long, Set<BlockPos>> stances = new HashMap<>();

    // 任何一项命中失败记录就拒绝；整站位被拒绝时，该站位的其他点击方式也一起被排除。
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

    // 这里只数被拒绝的点击方式，不包含单独拒绝的整站位数量。
    int rejectedCount(BuildTaskRecord.Target target) {
        return rejected.getOrDefault(target.pos().asLong(), Set.of()).size();
    }

    /**
     * 附近六格内发生已确认施工变化后，清掉相关目标的失败记录，让新露出的支撑面或路线有机会再试。
     */
    void changedNear(BlockPos changed) {
        rejected.keySet().removeIf(key -> BlockPos.of(key).distSqr(changed) <= 36);
        stances.keySet().removeIf(key -> BlockPos.of(key).distSqr(changed) <= 36);
    }

    void complete(BuildTaskRecord.Target target) {
        rejected.remove(target.pos().asLong()); stances.remove(target.pos().asLong());
    }
}
