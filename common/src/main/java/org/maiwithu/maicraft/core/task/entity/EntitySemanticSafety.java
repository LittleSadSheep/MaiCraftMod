// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.entity;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.IntentRuntime;

/** Shared, resource-agnostic interpretation of semantic entity relationships. */
public final class EntitySemanticSafety {
    private static final int LANDMARK_PROTECTION_RADIUS = 12;
    private static final int ENCLOSURE_RADIUS = 8;

    private enum ManagedArea { OPEN, ENCLOSED, UNKNOWN }

    private record ProtectedAreaEvidence(
            List<String> directProtectionReasons,
            List<String> containingAreaLabels) {
        private ProtectedAreaEvidence {
            directProtectionReasons = List.copyOf(directProtectionReasons);
            containingAreaLabels = List.copyOf(containingAreaLabels);
        }
    }

    private EntitySemanticSafety() {}

    /** Whether this live entity satisfies the requested relationship before protection checks. */
    public static boolean matchesRelation(
            Entity entity, GenericEntitySearchTaskRecord.Relation relation) {
        return switch (relation) {
            case ANY -> true;
            case HOSTILE -> isHostile(entity);
            case WILD -> entity instanceof Mob && !isHostile(entity);
            case UNOWNED -> entity instanceof LivingEntity && !(entity instanceof Player);
        };
    }

    /** An empty list is the only authorization to use the entity. */
    public static List<String> protectionReasons(
            LocalPlayer player,
            Entity entity,
            GenericEntitySearchTaskRecord.Relation relation,
            List<String> protectedLabels,
            boolean harmIntent) {
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        if (harmIntent && entity instanceof Player) reasons.add("player");

        boolean unownedEvidenceRequired = harmIntent
                || relation == GenericEntitySearchTaskRecord.Relation.WILD
                || relation == GenericEntitySearchTaskRecord.Relation.UNOWNED;
        if (unownedEvidenceRequired) {
            if (entity.hasCustomName()) reasons.add("named");
            if (entity instanceof TamableAnimal tame && tame.isTame()) reasons.add("tamed");
            if (entity instanceof Leashable leashable && leashable.isLeashed()) {
                reasons.add("leashed");
            }
            if (entity instanceof OwnableEntity ownable && ownable.getOwnerUUID() != null) {
                reasons.add("owned");
            }
            if (entity.getVehicle() != null) reasons.add("in_vehicle");
            if (!entity.getPassengers().isEmpty()) reasons.add("carrying_passenger");
        }

        ProtectedAreaEvidence areaEvidence = protectedAreaEvidence(
                player, entity.blockPosition(), protectedLabels);
        reasons.addAll(areaEvidence.directProtectionReasons());
        if (unownedEvidenceRequired && !areaEvidence.containingAreaLabels().isEmpty()) {
            ManagedArea managed = enclosureAt(player.clientLevel, entity.blockPosition());
            if (managed == ManagedArea.ENCLOSED) {
                for (String label : areaEvidence.containingAreaLabels()) {
                    reasons.add("enclosed_in_protected_area:" + label);
                }
            }
        }
        return List.copyOf(reasons);
    }

    private static boolean isHostile(Entity entity) {
        return entity instanceof Enemy
                || entity.getType().getCategory() == MobCategory.MONSTER;
    }

    private static ProtectedAreaEvidence protectedAreaEvidence(
            LocalPlayer player, BlockPos position, List<String> protectedLabels) {
        LinkedHashSet<String> containingAreas = new LinkedHashSet<>();
        IntentRuntime runtime = IntentRuntime.get();
        String dimension = player.level().dimension().location().toString();
        for (String label : protectedLabels == null ? List.<String>of() : protectedLabels) {
            IntentRuntime.Landmark landmark = runtime.landmark(label);
            // A label without a resolved location proves nothing about this entity. Likewise, a
            // remembered landmark is only area context: the point/radius is not itself a fence.
            // Physical enclosure evidence below is what turns that context into protection.
            if (landmark != null
                    && landmark.areaRole()
                            == IntentRuntime.LandmarkAreaRole.MANAGED_SETTLEMENT
                    && insideLandmark(position, landmark, dimension)) {
                containingAreas.add(landmark.label());
            }
        }
        /*
         * Human labels are never policy.  Only a caller-selected protected label carrying the
         * durable managed-settlement role can establish area context; physical enclosure remains
         * independently required by the caller above.
         */
        return new ProtectedAreaEvidence(
                List.of(), List.copyOf(containingAreas));
    }

    private static boolean insideLandmark(
            BlockPos position, IntentRuntime.Landmark landmark, String dimension) {
        Goal.WorldPosition center = landmark.position();
        if (center.dimension() != null && !center.dimension().equals(dimension)) return false;
        long dx = (long) position.getX() - center.x();
        long dz = (long) position.getZ() - center.z();
        return dx * dx + dz * dz
                <= (long) LANDMARK_PROTECTION_RADIUS * LANDMARK_PROTECTION_RADIUS;
    }

    /**
     * Loaded-only physical enclosure evidence.  Callers must additionally prove that the entity is
     * inside a known semantic area; an arbitrary local terrain pocket is never protection by itself.
     */
    private static ManagedArea enclosureAt(ClientLevel level, BlockPos origin) {
        int y = origin.getY();
        BlockPos start = new BlockPos(origin.getX(), y, origin.getZ());
        if (!openBodyCell(level, start)) return ManagedArea.UNKNOWN;
        Deque<BlockPos> queue = new ArrayDeque<>();
        Set<Long> visited = new java.util.HashSet<>();
        queue.add(start);
        visited.add(BlockPos.asLong(start.getX(), 0, start.getZ()));
        while (!queue.isEmpty() && visited.size() <= 512) {
            BlockPos cell = queue.removeFirst();
            int dx = cell.getX() - start.getX();
            int dz = cell.getZ() - start.getZ();
            if (Math.abs(dx) >= ENCLOSURE_RADIUS || Math.abs(dz) >= ENCLOSURE_RADIUS) {
                return ManagedArea.OPEN;
            }
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos next = cell.relative(direction);
                if (!level.isLoaded(next) || !level.isLoaded(next.above())) {
                    return ManagedArea.UNKNOWN;
                }
                long key = BlockPos.asLong(next.getX(), 0, next.getZ());
                if (visited.add(key) && openBodyCell(level, next)) queue.addLast(next);
            }
        }
        return ManagedArea.ENCLOSED;
    }

    private static boolean openBodyCell(ClientLevel level, BlockPos feet) {
        return level.isLoaded(feet) && level.isLoaded(feet.above())
                && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty();
    }
}
