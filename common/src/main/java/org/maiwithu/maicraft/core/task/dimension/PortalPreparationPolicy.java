// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord.Source;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy;

/** 传送门操作授权与路线挖掘许可及稀有物品消耗许可相互独立。 */
public record PortalPreparationPolicy(boolean enabled, boolean allowRareConsumables, boolean allowCombat,
                                      int maxStructureDistance, MaterialPolicy materialPolicy,
                                      List<Source> allowedSources, List<String> protectedLabels, Method method,
                                      int resourceSearchDistance, BuildTaskRecord.SpoilPolicy spoilPolicy) {
    public static final int DEFAULT_RESOURCE_SEARCH_DISTANCE = 768;
    /** 模型明确选择浇筑手法；执行器不会因为缺钻石而擅自替换已选的建门方案。 */
    public enum Method {
        OBSIDIAN, LAVA_CAST;
        public static Method parse(String value) {
            return switch (value == null ? "obsidian" : value) {
                case "obsidian" -> OBSIDIAN;
                case "lava_cast" -> LAVA_CAST;
                default -> throw new IllegalArgumentException("portal_method must be obsidian or lava_cast");
            };
        }
    }

    public PortalPreparationPolicy(boolean enabled, boolean rare, boolean combat, int distance, MaterialPolicy policy,
                                   List<Source> sources, List<String> labels) {
        this(enabled, rare, combat, distance, policy, sources, labels, Method.OBSIDIAN);
    }
    public PortalPreparationPolicy(boolean enabled, boolean rare, boolean combat, int distance, MaterialPolicy policy,
                                   List<Source> sources, List<String> labels, Method method) {
        this(enabled, rare, combat, distance, policy, sources, labels, method, DEFAULT_RESOURCE_SEARCH_DISTANCE);
    }
    public PortalPreparationPolicy(boolean enabled, boolean rare, boolean combat, int distance, MaterialPolicy policy,
                                   List<Source> sources, List<String> labels, Method method, int resourceSearchDistance) {
        this(enabled, rare, combat, distance, policy, sources, labels, method, resourceSearchDistance,
                BuildTaskRecord.SpoilPolicy.DEPOSIT);
    }
    public static final PortalPreparationPolicy DISABLED = new PortalPreparationPolicy(
            false, false, false, 4096, MaterialPolicy.ORDINARY, List.of(), List.of());

    public PortalPreparationPolicy {
        method = method == null ? Method.OBSIDIAN : method;
        spoilPolicy = spoilPolicy == null ? BuildTaskRecord.SpoilPolicy.DEPOSIT : spoilPolicy;
        // 零表示仅使用已加载资源；正数授权缺水或缺池时的有界跑图，不改变矿物和稀有物品的消费许可。
        resourceSearchDistance = checkResourceSearchDistance(resourceSearchDistance);
        maxStructureDistance = Math.clamp(maxStructureDistance, 128, 4096);
        materialPolicy = materialPolicy == null ? MaterialPolicy.ORDINARY : materialPolicy;
        allowedSources = allowedSources == null ? List.of() : List.copyOf(allowedSources);
        protectedLabels = protectedLabels == null ? List.of() : protectedLabels.stream().map(String::strip)
                .filter(s -> !s.isEmpty()).distinct().toList();
        if (protectedLabels.size() > 64) throw new IllegalArgumentException("protected_labels accepts at most 64 values");
    }

    public static PortalPreparationPolicy parse(JsonObject input) {
        return new PortalPreparationPolicy(flag(input, "prepare_portal"), flag(input, "allow_rare_consumables"),
                flag(input, "allow_combat"), input.has("max_search_distance") ? input.get("max_search_distance").getAsInt() : 4096,
                MaterialPolicy.parse(input.has("material_policy") ? input.get("material_policy").getAsString() : null),
                SemanticMaterialSupplyCoordinator.parseSources(strings(input, "allowed_sources")), strings(input, "protected_labels"),
                Method.parse(input.has("portal_method") ? input.get("portal_method").getAsString() : null),
                input.has("max_resource_search_distance") ? input.get("max_resource_search_distance").getAsInt() : DEFAULT_RESOURCE_SEARCH_DISTANCE,
                BuildTaskRecord.SpoilPolicy.parse(input.has("spoil_policy") ? input.get("spoil_policy").getAsString() : null));
    }
    /** 计划期就核对余土处置授权的枚举值；非法值不能等角色到池边才被拒绝。 */
    public static BuildTaskRecord.SpoilPolicy checkSpoilPolicy(JsonObject input) {
        return BuildTaskRecord.SpoilPolicy.parse(input.has("spoil_policy") ? input.get("spoil_policy").getAsString() : null);
    }
    /** 单独备门、跨维度和里程碑共用这个物理跑图范围，避免包装层漏传后退回仅就近查找。 */
    public static int checkResourceSearchDistance(int distance) {
        if (distance != 0 && (distance < 64 || distance > 2048))
            throw new IllegalArgumentException("max_resource_search_distance must be 0 or 64..2048");
        return distance;
    }
    private static boolean flag(JsonObject input, String key) { return input.has(key) && input.get(key).getAsBoolean(); }
    private static List<String> strings(JsonObject input, String key) {
        if (!input.has(key)) return List.of();
        List<String> values = new ArrayList<>();
        input.getAsJsonArray(key).forEach(value -> values.add(value.getAsString()));
        return values;
    }

    List<Source> sources(boolean mayAlterTerrain) {
        var sources = new ArrayList<>(SemanticMaterialSupplyCoordinator.resolveSources(materialPolicy, allowedSources));
        if (!mayAlterTerrain) sources.remove(Source.MINE);
        if (!allowCombat) sources.remove(Source.HUNT);
        else if (allowedSources.isEmpty() && materialPolicy != MaterialPolicy.INVENTORY_ONLY) sources.add(Source.HUNT);
        return List.copyOf(sources);
    }
}
