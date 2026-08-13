package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.scan.TargetIndex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Compiles high-level, generally useful play intents into the existing body tools.
 * Runtime entity ids, click positions and inventory slots never cross the semantic
 * boundary: this adapter derives them from the currently loaded client world.
 */
public final class GeneralAbilityAdapter {

    public static final String COMBAT = "maicraft:combat";
    public static final String INTERACT = "maicraft:interact";
    public static final String FOLLOW = "maicraft:follow";
    public static final String CONSUME = "maicraft:consume";
    public static final String EQUIP = "maicraft:equip";
    public static final String FISH = "maicraft:fish";
    public static final String DROP = "maicraft:drop_items";
    public static final String CONTAINER = "maicraft:use_container";
    public static final String MANAGE_CONTAINER = "maicraft:manage_container";
    public static final String FIND_ENTITY = "maicraft:find_entity";

    private static final Set<String> ABILITIES = Set.of(
            COMBAT, INTERACT, FOLLOW, CONSUME, EQUIP, FISH, DROP, CONTAINER, MANAGE_CONTAINER,
            FIND_ENTITY);
    private static final Set<String> EXECUTION_FIELDS = Set.of(
            "entity_id", "entity_ids", "entity_uuid", "x", "y", "z", "button",
            "hold_ticks", "slot_index", "source_slot", "destination_slot", "from_slot",
            "to_slot", "slot", "slots", "click", "clicks", "container_id", "menu_id",
            "moves");
    private static final Set<String> EQUIPMENT_SLOTS = Set.of(
            "mainhand", "offhand", "head", "chest", "legs", "feet", "armor");

    private GeneralAbilityAdapter() {}

    /** Registration surface for {@link AbilityAdapter}; no global state is installed here. */
    public static Set<String> abilities() {
        return ABILITIES;
    }

    public static boolean supports(String ability) {
        return ABILITIES.contains(ability);
    }

    /** Package-local because {@link IntentAction} is deliberately an intent-runtime detail. */
    static IntentAction adapt(Goal goal, LocalPlayer player, IntentRuntime runtime) {
        JsonObject parameters = goal.parameters();
        for (String field : EXECUTION_FIELDS) {
            if (parameters.has(field)) {
                return decision(goal,
                        "`" + field + "` is an execution detail. Describe the semantic target instead; "
                                + "the Mod resolves loaded-world ids, positions and clicks.",
                        List.of(option("retry", "Retry with a semantic target only."),
                                option("cancel", "Cancel this action.")), null);
            }
        }
        return switch (goal.ability()) {
            case COMBAT -> combat(goal, player);
            case INTERACT -> interact(goal, player, runtime, false);
            case FOLLOW -> follow(goal, player);
            case CONSUME -> consume(goal, player);
            case EQUIP -> equip(goal, player);
            case FISH -> fish(goal, player);
            case DROP -> drop(goal, player);
            case CONTAINER -> interact(goal, player, runtime, true);
            case MANAGE_CONTAINER -> manageContainer(goal);
            case FIND_ENTITY -> findEntity(goal);
            default -> throw new IllegalArgumentException("unsupported general ability: " + goal.ability());
        };
    }

    private static IntentAction manageContainer(Goal goal) {
        JsonObject p = goal.parameters();
        String operation = lower(string(p, "operation"));
        if (operation == null || !Set.of("deposit", "withdraw", "balance").contains(operation)) {
            return decision(goal, "Container management needs operation=deposit, withdraw or balance.",
                    List.of(option("retry", "Choose one semantic inventory operation."),
                            option("cancel", "Cancel container management.")), null);
        }

        LinkedHashSet<String> itemIds = new LinkedHashSet<>();
        String one = string(p, "item_id");
        if (one != null) itemIds.add(one);
        if (p.has("item_ids")) {
            if (!p.get("item_ids").isJsonArray()) {
                return decision(goal, "item_ids must be an array of namespaced item ids.",
                        List.of(option("retry", "Provide valid semantic item ids."),
                                option("cancel", "Cancel container management.")), null);
            }
            for (var element : p.getAsJsonArray("item_ids")) {
                if (!element.isJsonPrimitive()) itemIds.add("");
                else itemIds.add(element.getAsString());
            }
        }
        List<String> invalidItems = itemIds.stream().filter(value -> {
            ResourceLocation id = ResourceLocation.tryParse(value);
            return id == null || !BuiltInRegistries.ITEM.containsKey(id)
                    || BuiltInRegistries.ITEM.get(id) == net.minecraft.world.item.Items.AIR;
        }).toList();
        if (!invalidItems.isEmpty()) {
            return decision(goal, "The item selector contains unknown namespaced items: "
                            + invalidItems,
                    List.of(option("retry", "Use registered item ids only."),
                            option("cancel", "Cancel container management.")), null);
        }
        String tag = string(p, "tag");
        if (itemIds.isEmpty() == (tag == null)) {
            return decision(goal, "Provide exactly one item selector: item_id/item_ids, or tag.",
                    List.of(option("retry", "Describe the semantic item group once."),
                            option("cancel", "Cancel container management.")), null);
        }
        if (tag != null && ResourceLocation.tryParse(tag) == null) {
            return decision(goal, "tag must be a namespaced item tag id.",
                    List.of(option("retry", "Provide a valid namespaced item tag."),
                            option("cancel", "Cancel container management.")), null);
        }

        Integer count;
        Integer targetCount;
        try {
            count = strictInteger(p, "count", 1,
                    org.maiwithu.maicraft.core.task.container.SemanticContainerTaskRecord.MAX_COUNT);
            targetCount = strictInteger(p, "target_count", 0,
                    org.maiwithu.maicraft.core.task.container.SemanticContainerTaskRecord.MAX_COUNT);
        } catch (IllegalArgumentException invalid) {
            return decision(goal, invalid.getMessage(),
                    List.of(option("retry", "Provide a bounded semantic item count."),
                            option("cancel", "Cancel container management.")), null);
        }
        if (count != null && targetCount != null) {
            return decision(goal, "count and target_count are different semantics; provide only one.",
                    List.of(option("retry", "Choose an amount to move or a final target count."),
                            option("cancel", "Cancel container management.")), null);
        }
        if ("balance".equals(operation) && targetCount == null) {
            return decision(goal, "balance requires the desired final main-inventory target_count.",
                    List.of(option("retry", "Provide target_count for the selected item group."),
                            option("cancel", "Cancel container management.")), null);
        }

        String blockId = blockId(goal, p);
        if (blockId != null) {
            ResourceLocation id = ResourceLocation.tryParse(blockId);
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
                return decision(goal, "Unknown container block_id: " + blockId,
                        List.of(option("retry", "Use a registered namespaced block id."),
                                option("cancel", "Cancel container management.")), null);
            }
        }
        Goal.SemanticTarget target = goal.target();
        String targetKind = target == null ? null : lower(target.kind());
        String landmark = target != null && ("landmark".equals(targetKind)
                || "area".equals(targetKind)) ? target.label() : null;
        boolean nearest = nearest(goal, p);
        if ("coordinates".equals(targetKind) || "prior_result".equals(targetKind)) {
            return decision(goal, "manage_container does not accept coordinates or prior menu details.",
                    List.of(option("retry", "Use block_id, a landmark, or nearest selection."),
                            option("cancel", "Cancel container management.")), null);
        }
        if (blockId == null && landmark == null && !nearest) {
            return decision(goal, "Which semantic container should be managed?",
                    List.of(option("retry", "Provide block_id, a landmark target, or selection=nearest."),
                            option("cancel", "Cancel container management.")), null);
        }
        String selection = lower(string(p, "selection"));
        if (selection != null && !Set.of("nearest", "unique").contains(selection)) {
            return decision(goal, "selection must be nearest or unique.",
                    List.of(option("retry", "Choose whether any nearest match is acceptable."),
                            option("cancel", "Cancel container management.")), null);
        }

        JsonObject args = new JsonObject();
        args.addProperty("operation", operation);
        if (!itemIds.isEmpty()) {
            JsonArray values = new JsonArray();
            itemIds.forEach(values::add);
            args.add("item_ids", values);
        } else args.addProperty("tag", tag);
        if (count != null) args.addProperty("count", count);
        if (targetCount != null) args.addProperty("target_count", targetCount);
        if (blockId != null) args.addProperty("block_id", blockId);
        if (landmark != null) args.addProperty("landmark_label", landmark);
        args.addProperty("selection", nearest ? "nearest" : "unique");
        args.addProperty("radius", integer(p, "radius",
                org.maiwithu.maicraft.core.task.container.SemanticContainerTaskRecord.DEFAULT_RADIUS,
                1, org.maiwithu.maicraft.core.task.container.SemanticContainerTaskRecord.MAX_RADIUS));
        if (p.has("protected_labels")) {
            if (!p.get("protected_labels").isJsonArray()) {
                return decision(goal, "protected_labels must be an array of remembered labels.",
                        List.of(option("retry", "Provide protected labels as strings."),
                                option("cancel", "Cancel container management.")), null);
            }
            args.add("protected_labels", p.get("protected_labels").deepCopy());
        }
        return new IntentAction.Tool("manage_container", args.toString());
    }

    private static IntentAction findEntity(Goal goal) {
        JsonObject p = goal.parameters();
        LinkedHashSet<String> requested = new LinkedHashSet<>();
        List<String> invalid = new ArrayList<>();
        if (p.has("entity_type_ids") && p.get("entity_type_ids").isJsonArray()) {
            for (var element : p.getAsJsonArray("entity_type_ids")) {
                if (element == null || !element.isJsonPrimitive()) {
                    invalid.add("non-string entry");
                    continue;
                }
                addEntityType(element.getAsString(), requested, invalid);
            }
        }
        String one = string(p, "entity_type_id");
        if (one != null) addEntityType(one, requested, invalid);
        Goal.SemanticTarget target = goal.target();
        if (requested.isEmpty() && target != null && target.label() != null
                && ("entity".equals(target.kind()) || "entity_type".equals(target.kind()))) {
            addEntityType(target.label(), requested, invalid);
        }
        if (!invalid.isEmpty()) {
            return decision(goal,
                    "find_entity received unknown or invalid namespaced entity types: " + invalid,
                    List.of(option("retry", "Retry with registered entity_type_ids only."),
                            option("cancel", "Cancel entity search.")), null);
        }
        if (requested.isEmpty()) {
            return decision(goal,
                    "find_entity needs one or more semantic entity_type_ids; runtime IDs and coordinates are not accepted.",
                    List.of(option("retry", "Provide namespaced entity_type_ids and an optional relationship."),
                            option("cancel", "Cancel entity search.")), null);
        }

        String relation = lower(string(p, "relation"));
        if (relation == null && target != null) relation = lower(target.relation());
        if (relation == null) relation = "any";
        if (!Set.of("wild", "hostile", "unowned", "any").contains(relation)) {
            return decision(goal,
                    "Unknown entity relationship '" + relation + "'.",
                    List.of(option("retry", "Use wild, hostile, unowned or any."),
                            option("cancel", "Cancel entity search.")), null);
        }

        JsonArray types = new JsonArray();
        requested.forEach(types::add);
        JsonObject args = new JsonObject();
        args.add("entity_type_ids", types);
        args.addProperty("relation", relation);
        args.addProperty("count", integer(p, "count", 1, 1, 32));
        args.addProperty("max_distance", integer(p, "max_distance", 512, 16, 2_048));
        if (bool(p, "may_alter_terrain", false)
                || bool(goal.preferences(), "may_alter_terrain", false)) {
            args.addProperty("may_alter_terrain", true);
        }
        if (p.has("protected_labels") && p.get("protected_labels").isJsonArray()) {
            args.add("protected_labels", p.get("protected_labels").deepCopy());
        }
        return new IntentAction.Tool("find_entity", args.toString());
    }

    private static void addEntityType(
            String raw, Set<String> requested, List<String> invalid) {
        ResourceLocation id = ResourceLocation.tryParse(raw);
        if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
            invalid.add(String.valueOf(raw));
        } else {
            requested.add(id.toString());
        }
    }

    private static IntentAction combat(Goal goal, LocalPlayer player) {
        JsonObject p = goal.parameters();
        if (!bool(p, "allow_harm", false)) {
