// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.lighting;

import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/** A bounded semantic lighting outcome; placement cells are deliberately absent. */
public final class SemanticLightAreaTaskRecord extends TaskRecord {
    public static final String TOOL_NAME = "light_area";
    public static final int MIN_RADIUS = 1;
    public static final int MAX_RADIUS = 48;
    public static final int DEFAULT_MAX_PASSES = 4;
    public static final int DEFAULT_MAX_PLACEMENTS = 192;

    static {
        TaskFactory.register(SemanticLightAreaTaskRecord.class,
                SemanticLightAreaCompanionTask::new);
    }

    public enum Coverage {
        ALL(1.0D),
        MOST(0.90D),
        CROP_GROWTH(1.0D),
        PLAYER_VISIBILITY(0.95D);

        private final double requiredRatio;

        Coverage(double requiredRatio) {
            this.requiredRatio = requiredRatio;
        }

        public double requiredRatio() {
            return requiredRatio;
        }

        public static Coverage parse(String value) {
            if (value == null || value.isBlank()) return MOST;
            return switch (value.strip().toLowerCase(Locale.ROOT)) {
                case "all" -> ALL;
                case "most" -> MOST;
                case "crop_growth" -> CROP_GROWTH;
                case "player_visibility" -> PLAYER_VISIBILITY;
                default -> throw new IllegalArgumentException(
                        "coverage must be all, most, crop_growth or player_visibility");
            };
        }
    }

    public enum Style {
        AUTO,
        GROUND,
        WALL,
        HANGING,
        UNOBTRUSIVE;

        public static Style parse(String value) {
            if (value == null || value.isBlank()) return AUTO;
            return switch (value.strip().toLowerCase(Locale.ROOT)) {
                case "auto" -> AUTO;
                case "ground", "floor", "fence" -> GROUND;
                case "wall" -> WALL;
                case "hanging", "ceiling" -> HANGING;
                case "unobtrusive", "hidden", "discreet" -> UNOBTRUSIVE;
                default -> throw new IllegalArgumentException(
                        "style must be auto, ground, wall, hanging or unobtrusive");
            };
        }
    }

    public enum PlacementPreference {
        COVERAGE_OPTIMAL,
        CENTRAL_UNPLANTED,
        UNOBTRUSIVE;

        public static PlacementPreference parse(String value) {
            if (value == null || value.isBlank()) return COVERAGE_OPTIMAL;
            return switch (value.strip().toLowerCase(Locale.ROOT)) {
                case "coverage_optimal" -> COVERAGE_OPTIMAL;
                case "central_unplanted" -> CENTRAL_UNPLANTED;
                case "unobtrusive" -> UNOBTRUSIVE;
                default -> throw new IllegalArgumentException(
                        "placement_preference must be coverage_optimal, central_unplanted or unobtrusive");
            };
        }
    }

    public final BlockPos center;
    /** Optional semantic area label retained for evidence/result reporting, never parsed as coordinates. */
    public final String semanticTarget;
    /** Resolve the nearest matching loaded connected component around {@link #center}. */
    public final boolean resolveLoadedComponent;
    public final int radius;
    public final int minimumLight;
    public final Coverage coverage;
    public final Style style;
    public final PlacementPreference placementPreference;
    public final List<String> lightPreferences;
    public final List<String> protectedLabels;
    public final SemanticMaterialSupplyCoordinator.MaterialPolicy materialPolicy;
    public final List<SemanticAcquireTaskRecord.Source> allowedSources;
    public final boolean allowHarm;
    public final int maxPasses;
    public final int maxPlacements;

    public SemanticLightAreaTaskRecord(
            String toolCallId,
            long deadlineGameTime,
            BlockPos center,
            String semanticTarget,
            boolean resolveLoadedComponent,
            int radius,
            int minimumLight,
            Coverage coverage,
            Style style,
            PlacementPreference placementPreference,
            List<String> lightPreferences,
            List<String> protectedLabels,
            SemanticMaterialSupplyCoordinator.MaterialPolicy materialPolicy,
            List<SemanticAcquireTaskRecord.Source> allowedSources,
            boolean allowHarm,
            int maxPasses,
            int maxPlacements) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.center = center.immutable();
        this.semanticTarget = semanticTarget == null || semanticTarget.isBlank()
                ? null : semanticTarget.strip();
        this.resolveLoadedComponent = resolveLoadedComponent;
        this.radius = Math.clamp(radius, MIN_RADIUS, MAX_RADIUS);
        this.minimumLight = Math.clamp(minimumLight, 1, 15);
        this.coverage = coverage == null ? Coverage.MOST : coverage;
        this.style = style == null ? Style.AUTO : style;
        this.placementPreference = placementPreference == null
                ? PlacementPreference.COVERAGE_OPTIMAL : placementPreference;
        if (this.placementPreference == PlacementPreference.CENTRAL_UNPLANTED
                && this.coverage != Coverage.CROP_GROWTH) {
            throw new IllegalArgumentException(
                    "central_unplanted placement_preference requires crop_growth coverage");
        }
        this.lightPreferences = clean(lightPreferences);
        this.protectedLabels = clean(protectedLabels);
        this.materialPolicy = materialPolicy == null
                ? SemanticMaterialSupplyCoordinator.MaterialPolicy.ORDINARY : materialPolicy;
        this.allowedSources = allowedSources == null ? List.of() : List.copyOf(allowedSources);
        this.allowHarm = allowHarm;
        this.maxPasses = Math.clamp(maxPasses, 1, 8);
        this.maxPlacements = Math.clamp(maxPlacements, 1, 512);
    }

    private static List<String> clean(List<String> values) {
        if (values == null) return List.of();
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .distinct()
                .toList();
    }

    /** Forces task registration at Mod initialization. */
    public static void ensureRegistered() {}

    @Override public String describe() {
        return "实测并补足半径 " + radius + " 格区域的方块亮度至 " + minimumLight
                + "（" + coverage.name().toLowerCase(Locale.ROOT) + "，"
                + placementPreference.name().toLowerCase(Locale.ROOT) + "）";
    }
}
