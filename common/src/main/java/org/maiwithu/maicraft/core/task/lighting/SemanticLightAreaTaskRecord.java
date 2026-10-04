// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.lighting;

import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.InternalAreaProtectionReceipt;
import org.maiwithu.maicraft.task.TaskRecord;

/** 区域补光的任务输入：保存区域种子与验收要求，具体灯位由实时观察生成，结果另由执行器收集。 */
public final class SemanticLightAreaTaskRecord extends TaskRecord
        implements InternalAreaProtectionReceipt {
    public static final String TOOL_NAME = "light_area";
    public static final int MIN_RADIUS = 1;
    /** Minecraft 实际世界边界，不代表施工或探索预算。 */
    public static final int MAX_EXPLICIT_RADIUS = 29_999_984;
    /** 显式预算的输入上限；只限制本任务的放置尝试，不承诺这些尝试一定点亮全部样本。 */
    public static final int MAX_EXPLICIT_PLACEMENTS = 24_000;

    static {
        TaskFactory.register(SemanticLightAreaTaskRecord.class,
                SemanticLightAreaCompanionTask::new);
    }

    public enum Coverage {
        // all 与作物模式都要求全部所选样本达标；most 和 player_visibility 允许留下少量暗格。
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
            // 未明确放宽覆盖率时，基地每个已观察可行走地面都必须达到目标亮度。
            if (value == null || value.isBlank()) return ALL;
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
        // wall/hanging 仅保留内部词表兼容；区域执行器会拒绝它们，不能据此宣称公开能力支持壁灯或吊灯布局。
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
    /** 可选语义区域标签，仅保留用于证据与结果报告，绝不解析为坐标。 */
    public final String semanticTarget;
    /** 以 {@link #center} 附近为起点，解析并闭合匹配的连通区域。 */
    public final boolean resolveLoadedComponent;
    /** 玩家可选指定的水平圆形边界；零只是内部“未指定”标记，公开半径要求至少一格。 */
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
    /** 零表示内部未设预算；火把快速轮按已提交灯位计数，普通施工轮按派发灯位计数，并非确认消耗数量。 */
    public final int maxPlacements;
    /** 保留实时世界中的精确保护条件，仅供语义父任务使用。 */
    private List<Footprint> internalAreaProtections = List.of();

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
            int maxPlacements) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.center = center.immutable();
        this.semanticTarget = semanticTarget == null || semanticTarget.isBlank()
                ? null : semanticTarget.strip();
        this.resolveLoadedComponent = resolveLoadedComponent;
        this.radius = radius <= 0 ? 0
                : Math.clamp(radius, MIN_RADIUS, MAX_EXPLICIT_RADIUS);
        this.minimumLight = Math.clamp(minimumLight, 1, 15);
        this.coverage = coverage == null ? Coverage.ALL : coverage;
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
        this.maxPlacements = maxPlacements <= 0 ? 0
                : Math.clamp(maxPlacements, 1, MAX_EXPLICIT_PLACEMENTS);
    }

    public boolean hasPlacementBudget() {
        return maxPlacements > 0;
    }

    public boolean hasExplicitRadius() {
        return radius > 0;
    }

    void retainInternalAreaProtection(Footprint footprint) {
        internalAreaProtections = footprint == null ? List.of() : List.of(footprint);
    }

    @Override
    public List<Footprint> internalAreaProtections() {
        return internalAreaProtections;
    }

    private static List<String> clean(List<String> values) {
        if (values == null) return List.of();
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .distinct()
                .toList();
    }

    /** 强制在模组初始化阶段注册任务。 */
    public static void ensureRegistered() {}

    @Override public String describe() {
        String scope = hasExplicitRadius()
                ? "玩家明确限定的半径 " + radius + " 格区域"
                : "从语义地标实测闭合的连通区域";
        return "实测并补足" + scope + "的方块亮度至 " + minimumLight
                + "（" + coverage.name().toLowerCase(Locale.ROOT) + "，"
                + placementPreference.name().toLowerCase(Locale.ROOT) + "）";
    }
}
