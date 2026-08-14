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
            return decision(goal,
                    "Combat can harm or kill entities. Confirm that harm is intended before the body acts.",
                    List.of(option("retry", "Retry with allow_harm=true if this harm is intended."),
                            option("skip", "Skip the combat step."),
                            option("cancel", "Cancel the task.")), null);
        }

        String mode = lower(string(p, "mode"));
        EntitySelector selector = selector(goal, p);
        if (("defend".equals(mode) || "defence".equals(mode)) && selector.empty()) {
            return new IntentAction.Tool("attack", "{}");
        }
        String selectorError = validateSelector(selector);
        if (selectorError != null) return invalidSelector(goal, selectorError);
        if (selector.empty()) {
            return decision(goal, "Who or what should be fought?",
                    List.of(option("retry", "Provide entity_type_id, entity_name, or player_name."),
                            option("cancel", "Cancel combat.")), null);
        }

        int radius = integer(p, "radius", 32, 4, 128);
        List<Entity> candidates = findEntities(player, selector, radius, true);
        if (candidates.isEmpty()) return missingEntity(goal, selector, radius);

        int requested = integer(p, "count", 1, 1, 20);
        boolean nearest = nearest(goal, p);
        if (requested == 1 && candidates.size() > 1 && !nearest) {
            return ambiguousEntities(goal, player, "Several loaded entities match the combat target.", candidates);
        }
        List<Entity> selected = candidates.subList(0, Math.min(requested, candidates.size()));
        List<Entity> risky = selected.stream().filter(GeneralAbilityAdapter::riskyHarmTarget).toList();
        if (!risky.isEmpty() && !bool(p, "confirm_risky_target", false)) {
            JsonObject facts = new JsonObject();
            facts.add("protected_or_non_hostile_candidates", entityFacts(player, risky));
            return decision(goal,
                    "The resolved target includes a player, tame/named entity, or non-hostile creature. "
                            + "This may be somebody's companion or farm animal.",
                    List.of(option("retry", "Retry with confirm_risky_target=true only after confirming this exact semantic target."),
                            option("replace_goal", "Choose a safer target or another way to reach the outcome."),
                            option("cancel", "Cancel combat.")), facts);
        }

        JsonArray ids = new JsonArray();
        selected.forEach(entity -> ids.add(entity.getId()));
        JsonObject args = new JsonObject();
        args.add("entity_ids", ids);
        return new IntentAction.Tool("attack", args.toString());
    }

    private static IntentAction follow(Goal goal, LocalPlayer player) {
        JsonObject p = goal.parameters();
        EntitySelector selector = selector(goal, p);
        String error = validateSelector(selector);
        if (error != null) return invalidSelector(goal, error);
        if (selector.empty()) {
            return decision(goal, "Who should be followed?",
                    List.of(option("retry", "Provide a player_name, entity_name, or entity_type_id."),
                            option("cancel", "Cancel following.")), null);
        }
        int radius = integer(p, "radius", 64, 4, 128);
        List<Entity> candidates = findEntities(player, selector, radius, false);
        if (candidates.isEmpty()) return missingEntity(goal, selector, radius);
        if (candidates.size() > 1 && !nearest(goal, p)) {
            return ambiguousEntities(goal, player, "Several loaded entities match the follow target.", candidates);
        }
        Entity selected = candidates.getFirst();
        JsonObject args = new JsonObject();
        args.addProperty("entity_id", selected.getId());
        args.addProperty("distance", integer(p, "distance", 3, 2, 16));
        args.addProperty("may_alter_terrain", bool(p, "may_alter_terrain", false));
        return new IntentAction.Tool("follow", args.toString());
    }

    private static IntentAction interact(
            Goal goal, LocalPlayer player, IntentRuntime runtime, boolean containerOnly) {
        JsonObject p = goal.parameters();
        if (containerOnly && (p.has("transfer") || p.has("deposit") || p.has("withdraw"))) {
            return decision(goal,
                    "Container slot movement is not part of `use_container`; this ability safely opens or uses "
                            + "a semantic container target only.",
                    List.of(option("replace_goal", "Open the container first, then request a semantic deposit/withdraw ability when available."),
                            option("cancel", "Cancel container use.")), null);
        }
        String purpose = lower(string(p, "purpose"));
        if ("attack".equals(purpose) || "break".equals(purpose)) {
            return decision(goal, "Destructive interaction must use the combat or mining ability.",
                    List.of(option("replace_goal", "Replace this with combat or mining so its safety policy applies."),
                            option("cancel", "Cancel interaction.")), null);
        }

        EntitySelector selector = selector(goal, p);
        String blockId = blockId(goal, p);
        if (!selector.empty() && blockId != null) {
            return decision(goal, "The target names both an entity and a block. Which one should be used?",
                    List.of(option("retry", "Retry with exactly one semantic target kind."),
                            option("cancel", "Cancel interaction.")), null);
        }
        if (!selector.empty()) {
            if (containerOnly) {
                return decision(goal, "`use_container` currently opens loaded block containers, not entity inventories.",
                        List.of(option("replace_goal", "Use a block container target."),
                                option("cancel", "Cancel container use.")), null);
            }
            String error = validateSelector(selector);
            if (error != null) return invalidSelector(goal, error);
            List<Entity> candidates = findEntities(player, selector,
                    integer(p, "radius", 48, 4, 128), false);
            if (candidates.isEmpty()) return missingEntity(goal, selector, integer(p, "radius", 48, 4, 128));
            if (candidates.size() > 1 && !nearest(goal, p)) {
                return ambiguousEntities(goal, player,
                        "Several loaded entities match the interaction target.", candidates);
            }
            String itemId = itemId(p);
            IntentAction missingItem = requireInventoryItem(goal, player, itemId);
            if (missingItem != null) return missingItem;
            JsonObject args = new JsonObject();
            args.addProperty("button", "right");
            args.addProperty("entity_id", candidates.getFirst().getId());
            if (itemId != null) args.addProperty("item_id", itemId);
            return new IntentAction.Tool("interact_entity", args.toString());
        }
        if (blockId == null) {
            return decision(goal, containerOnly ? "Which loaded container block should be opened?"
                            : "Which block or entity should be used?",
                    List.of(option("retry", containerOnly
                                    ? "Provide block_id, such as a namespaced chest or modded storage block."
                                    : "Provide block_id, entity_type_id, entity_name, or player_name."),
                            option("cancel", "Cancel interaction.")), null);
        }
        return interactBlock(goal, player, runtime, blockId, itemId(p));
    }

    private static IntentAction interactBlock(
            Goal goal, LocalPlayer player, IntentRuntime runtime, String rawBlockId, String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(rawBlockId);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            return decision(goal, "Unknown block id: " + rawBlockId,
                    List.of(option("retry", "Retry with a valid namespaced block id."),
                            option("cancel", "Cancel interaction.")), null);
        }
        IntentAction missingItem = requireInventoryItem(goal, player, itemId);
        if (missingItem != null) return missingItem;

        ClientLevel level = player.clientLevel;
        Block block = BuiltInRegistries.BLOCK.get(id);
        BlockPos center = semanticCenter(goal, player, runtime);
        int radius = integer(goal.parameters(), "radius", 64, 4, 128);
        int chunkRadius = Math.max(1, (radius + 15) / 16);
        TargetIndex.Result query;
        TargetIndex.register(level, List.of(block));
        try {
            query = TargetIndex.query(level, center, List.of(block), 8, chunkRadius, 384);
        } finally {
            TargetIndex.unregister(level, List.of(block));
        }
        List<BlockPos> hits = query.hits().stream()
                .filter(pos -> squaredHorizontal(pos, center) <= (long) radius * radius)
                .filter(pos -> level.getBlockState(pos).is(block))
                .sorted(Comparator.comparingLong(pos -> squared(pos, center)))
                .toList();
        if (hits.isEmpty()) {
            JsonObject facts = new JsonObject();
            facts.addProperty("block_id", id.toString());
            facts.addProperty("searched_loaded_radius", radius);
            facts.addProperty("loaded_scan_complete", query.complete());
            return decision(goal, "No matching block is visible in the currently loaded search area.",
                    List.of(option("recover", "Travel or explore to load the likely area, then retry."),
                            option("replace_goal", "Choose another visible block target."),
                            option("cancel", "Cancel interaction.")), facts);
        }
        if (hits.size() > 1 && !nearest(goal, goal.parameters())) {
            JsonObject facts = new JsonObject();
            facts.addProperty("block_id", id.toString());
            facts.add("candidates", blockFacts(center, hits));
            return decision(goal, "Several loaded blocks match this semantic target.",
                    List.of(option("retry", "Retry with selection=nearest or name a landmark/area relation."),
                            option("cancel", "Cancel interaction.")), facts);
        }

        BlockPos target = hits.getFirst();
        JsonObject use = new JsonObject();
        use.addProperty("button", "right");
        use.addProperty("x", target.getX());
        use.addProperty("y", target.getY());
        use.addProperty("z", target.getZ());
        if (itemId != null) use.addProperty("item_id", itemId);
        if (player.getEyePosition().distanceToSqr(Vec3.atCenterOf(target)) <= 4.5D * 4.5D) {
            return new IntentAction.Tool("interact_at", use.toString());
        }
        BlockPos stand = interactionStand(level, target, player.blockPosition());
        if (stand == null) {
            JsonObject facts = new JsonObject();
            facts.addProperty("block_id", id.toString());
            facts.addProperty("distance", roundedDistance(player, target));
            return decision(goal, "The loaded target has no verified standable interaction position nearby.",
                    List.of(option("recover", "Clear or approach the obstruction, then retry."),
                            option("replace_goal", "Choose another matching target."),
                            option("cancel", "Cancel interaction.")), facts);
        }
        JsonObject travel = new JsonObject();
        travel.addProperty("x", stand.getX() + 0.5D);
        travel.addProperty("y", stand.getY());
        travel.addProperty("z", stand.getZ() + 0.5D);
        travel.addProperty("may_alter_terrain", bool(goal.parameters(), "may_alter_terrain", false));
        return new IntentAction.Chain(List.of(
                new IntentAction.Tool("goto", travel.toString()),
                new IntentAction.Tool("interact_at", use.toString())));
    }

    private static IntentAction consume(Goal goal, LocalPlayer player) {
        JsonObject p = goal.parameters();
        if (player.getFoodData().getFoodLevel() >= 20) {
            return decision(goal, "Hunger is already full, so eating would not meet the requested outcome.",
                    List.of(option("skip", "Skip eating for now."),
                            option("cancel", "Cancel consumption.")), null);
        }
        String requested = itemId(p);
        if (requested == null && goal.target() != null && "item".equals(goal.target().kind())) {
            requested = goal.target().label();
        }
        if (requested != null) {
            ResourceLocation id = ResourceLocation.tryParse(requested);
            if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
                return invalidItem(goal, requested);
            }
            Item item = BuiltInRegistries.ITEM.get(id);
            ItemStack stack = firstStack(player, item);
            if (stack == null) return missingItem(goal, requested);
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food == null) {
                return decision(goal, requested + " is not food supported by the timed consume action.",
                        List.of(option("replace_goal", "Choose an edible inventory item or a dedicated use-item action."),
                                option("cancel", "Cancel consumption.")), null);
            }
            if (!food.effects().isEmpty() && !bool(p, "allow_effects", false)) {
                JsonObject facts = new JsonObject();
                facts.addProperty("item_id", requested);
                facts.addProperty("effect_entries", food.effects().size());
                return decision(goal, "The named food can apply effects; the safe-food policy will not consume it implicitly.",
                        List.of(option("retry", "Retry with allow_effects=true only if these effects are intended."),
                                option("replace_goal", "Choose effect-free food."),
                                option("cancel", "Cancel consumption.")), facts);
            }
            return eatTool(requested);
        }

        int missingHunger = 20 - player.getFoodData().getFoodLevel();
        List<FoodChoice> choices = foodChoices(player);
        List<FoodChoice> safe = choices.stream().filter(choice -> choice.food().effects().isEmpty())
                .sorted(Comparator.comparingInt((FoodChoice choice) ->
                                Math.abs(choice.food().nutrition() - missingHunger))
                        .thenComparing(Comparator.comparingDouble(
                                (FoodChoice choice) -> choice.food().saturation()).reversed()))
                .toList();
        if (safe.isEmpty()) {
            JsonObject facts = new JsonObject();
            facts.add("available_food", foodFacts(choices));
            return decision(goal, choices.isEmpty()
                            ? "There is no food in the inventory."
                            : "Only foods with effects are available; the safe-food policy will not choose one silently.",
                    List.of(option("recover", "Acquire ordinary effect-free food, then retry."),
                            option("retry", "Name a specific food and set allow_effects=true if its effects are intended."),
                            option("cancel", "Cancel consumption.")), facts);
        }
        return eatTool(safe.getFirst().itemId());
    }

    private static IntentAction equip(Goal goal, LocalPlayer player) {
        JsonObject p = goal.parameters();
        String action = lower(string(p, "action"));
        if (action == null) action = "equip";
        String slot = lower(string(p, "slot"));
        if (slot != null && !EQUIPMENT_SLOTS.contains(slot)) {
            return decision(goal, "Unknown semantic equipment slot: " + slot,
                    List.of(option("retry", "Use mainhand, offhand, head, chest, legs, feet, or armor."),
                            option("cancel", "Cancel equipment change.")), null);
        }
        if ("unequip".equals(action)) {
            if (slot == null) {
                return decision(goal, "Which equipment slot should be cleared?",
                        List.of(option("retry", "Provide a semantic slot; armor means all four armor pieces."),
                                option("cancel", "Cancel equipment change.")), null);
            }
            JsonObject args = new JsonObject();
            args.addProperty("action", "unequip");
            args.addProperty("slot", slot);
            return new IntentAction.Tool("equip_item", args.toString());
        }
        if (!"equip".equals(action)) {
            return decision(goal, "Unsupported equipment action: " + action,
                    List.of(option("retry", "Use action=equip or action=unequip."),
                            option("cancel", "Cancel equipment change.")), null);
        }
        if ("armor".equals(slot)) {
            return decision(goal, "slot=armor is only meaningful when unequipping all armor.",
                    List.of(option("retry", "Choose one armor slot or omit slot for automatic routing."),
                            option("cancel", "Cancel equipment change.")), null);
        }

        String itemId = itemId(p);
        if (itemId == null && goal.target() != null && "item".equals(goal.target().kind())) {
            itemId = goal.target().label();
        }
        if (itemId == null) {
            if (slot == null) {
                return decision(goal, "Which item should be equipped?",
                        List.of(option("retry", "Provide item_id, or a slot with exactly one compatible inventory item."),
                                option("cancel", "Cancel equipment change.")), null);
            }
            EquipmentSlot equipmentSlot = equipmentSlot(slot);
            Map<String, ItemStack> matching = new LinkedHashMap<>();
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (!stack.isEmpty() && player.getEquipmentSlotForItem(stack) == equipmentSlot) {
                    matching.putIfAbsent(itemId(stack), stack);
                }
            }
            if (matching.isEmpty()) {
                return decision(goal, "No compatible item for " + slot + " is in the inventory.",
                        List.of(option("recover", "Acquire suitable equipment, then retry."),
                                option("cancel", "Cancel equipment change.")), null);
            }
            if (matching.size() > 1) {
                JsonObject facts = new JsonObject();
                JsonArray ids = new JsonArray();
                matching.keySet().forEach(ids::add);
                facts.add("compatible_item_ids", ids);
                return decision(goal, "Several inventory items fit the requested equipment slot.",
                        List.of(option("retry", "Retry with the intended item_id."),
                                option("cancel", "Cancel equipment change.")), facts);
            }
            itemId = matching.keySet().iterator().next();
        }
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return invalidItem(goal, itemId);
        if (firstStack(player, BuiltInRegistries.ITEM.get(id)) == null) return missingItem(goal, itemId);
        JsonObject args = new JsonObject();
        args.addProperty("action", "equip");
        args.addProperty("item_id", itemId);
        if (slot != null) args.addProperty("slot", slot);
        return new IntentAction.Tool("equip_item", args.toString());
    }

    private static IntentAction fish(Goal goal, LocalPlayer player) {
        ResourceLocation rodId = ResourceLocation.tryParse("minecraft:fishing_rod");
        Item rod = BuiltInRegistries.ITEM.get(rodId);
        if (firstStack(player, rod) == null) {
            JsonObject facts = new JsonObject();
            facts.addProperty("missing_item_id", "minecraft:fishing_rod");
            return decision(goal, "Fishing needs a fishing rod, but none is in the inventory.",
                    List.of(option("recover", "Acquire or craft a fishing rod, then retry."),
                            option("skip", "Skip fishing."),
                            option("cancel", "Cancel the task.")), facts);
        }
        JsonObject args = new JsonObject();
        args.addProperty("count", integer(goal.parameters(), "count", 1, 1, 64));
        return new IntentAction.Tool("fish", args.toString());
    }

    private static IntentAction drop(Goal goal, LocalPlayer player) {
        JsonObject p = goal.parameters();
        String itemId = itemId(p);
        if (itemId == null && goal.target() != null && "item".equals(goal.target().kind())) {
            itemId = goal.target().label();
        }
        if (itemId == null || !p.has("count")) {
            return decision(goal, "Dropping is irreversible; both item_id and an explicit count are required.",
                    List.of(option("retry", "Provide the namespaced item and exact count to drop."),
                            option("cancel", "Cancel dropping.")), null);
        }
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return invalidItem(goal, itemId);
        int count = integer(p, "count", 0, 0, 999);
        if (count < 1) {
            return decision(goal, "Drop count must be between 1 and 999.",
                    List.of(option("retry", "Provide a positive count."),
                            option("cancel", "Cancel dropping.")), null);
        }
        int available = countItem(player, BuiltInRegistries.ITEM.get(id));
        if (available == 0) return missingItem(goal, itemId);
        if (count > available) {
            JsonObject facts = new JsonObject();
            facts.addProperty("item_id", itemId);
            facts.addProperty("requested_count", count);
            facts.addProperty("available_count", available);
            return decision(goal, "The requested drop count exceeds the inventory count.",
                    List.of(option("retry", "Retry with a count no greater than available_count."),
                            option("cancel", "Cancel dropping.")), facts);
        }
        JsonObject args = new JsonObject();
        args.addProperty("item_id", itemId);
        args.addProperty("count", count);
        return new IntentAction.Tool("drop_items", args.toString());
    }

    private static EntitySelector selector(Goal goal, JsonObject p) {
        String type = firstString(p, "entity_type_id", "entity_type");
        String playerName = string(p, "player_name");
        String entityName = string(p, "entity_name");
        boolean hostileOnly = false;
        Goal.SemanticTarget target = goal.target();
        if (target != null) {
            String kind = lower(target.kind());
            String label = target.label();
            if (playerName == null && "player".equals(kind)) playerName = label;
            if (type == null && List.of("entity_type", "mob_type").contains(kind)) type = label;
            if (List.of("hostile", "nearest_hostile").contains(kind)) hostileOnly = true;
            if (entityName == null && "entity".equals(kind) && label != null) {
                ResourceLocation possibleType = ResourceLocation.tryParse(label);
                if (possibleType != null && BuiltInRegistries.ENTITY_TYPE.containsKey(possibleType)) type = label;
                else entityName = label;
            }
        }
        ResourceLocation typeId = type == null ? null : ResourceLocation.tryParse(type);
        return new EntitySelector(type, typeId, playerName, entityName, hostileOnly);
    }

    private static String validateSelector(EntitySelector selector) {
        if (selector.rawType() != null && (selector.typeId() == null
                || !BuiltInRegistries.ENTITY_TYPE.containsKey(selector.typeId()))) {
            return "Unknown entity type id: " + selector.rawType();
        }
        return null;
    }

    private static List<Entity> findEntities(
            LocalPlayer player, EntitySelector selector, int radius, boolean combat) {
        AABB area = player.getBoundingBox().inflate(radius);
        return player.clientLevel.getEntities(player, area, entity -> {
                    if (entity == player || entity.isRemoved() || !entity.isAlive()) return false;
                    if (combat && !entity.isAttackable()) return false;
                    if (selector.typeId() != null && !BuiltInRegistries.ENTITY_TYPE
                            .getKey(entity.getType()).equals(selector.typeId())) return false;
                    if (selector.hostileOnly() && !(entity instanceof Enemy)) return false;
                    if (selector.playerName() != null) {
                        if (!(entity instanceof Player other)
                                || !other.getGameProfile().getName().equalsIgnoreCase(selector.playerName())) {
                            return false;
                        }
                    }
                    return selector.entityName() == null
                            || semanticName(entity).equalsIgnoreCase(selector.entityName());
                }).stream()
                .sorted(Comparator.comparingDouble(player::distanceToSqr))
                .toList();
    }

    private static boolean riskyHarmTarget(Entity entity) {
        return entity instanceof Player || !(entity instanceof Enemy)
                || entity.hasCustomName()
                || entity instanceof TamableAnimal tame && tame.isTame();
    }

    private static IntentAction ambiguousEntities(
            Goal goal, LocalPlayer player, String question, List<Entity> entities) {
        JsonObject facts = new JsonObject();
        facts.add("candidates", entityFacts(player, entities.stream().limit(8).toList()));
        return decision(goal, question,
                List.of(option("retry", "Retry with a unique name, narrower type, or selection=nearest."),
                        option("cancel", "Cancel this action.")), facts);
    }

    private static IntentAction missingEntity(Goal goal, EntitySelector selector, int radius) {
        JsonObject facts = new JsonObject();
        if (selector.typeId() != null) facts.addProperty("entity_type_id", selector.typeId().toString());
        if (selector.playerName() != null) facts.addProperty("player_name", selector.playerName());
        if (selector.entityName() != null) facts.addProperty("entity_name", selector.entityName());
        facts.addProperty("searched_loaded_radius", radius);
        return decision(goal, "No matching entity is currently loaded and visible to the client.",
                List.of(option("recover", "Travel or explore to load the target area, then retry."),
                        option("replace_goal", "Choose another visible semantic target."),
                        option("cancel", "Cancel this action.")), facts);
    }

    private static JsonArray entityFacts(LocalPlayer player, List<Entity> entities) {
        JsonArray result = new JsonArray();
        for (Entity entity : entities) {
            JsonObject fact = new JsonObject();
            fact.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            fact.addProperty("name", semanticName(entity));
            fact.addProperty("distance", roundedDistance(player, entity.blockPosition()));
            fact.addProperty("hostile", entity instanceof Enemy);
            fact.addProperty("named", entity.hasCustomName());
            fact.addProperty("tamed", entity instanceof TamableAnimal tame && tame.isTame());
            result.add(fact);
        }
        return result;
    }

    private static JsonArray blockFacts(BlockPos center, List<BlockPos> positions) {
        JsonArray result = new JsonArray();
        positions.stream().limit(8).forEach(pos -> {
            JsonObject fact = new JsonObject();
            fact.addProperty("distance", Math.round(Math.sqrt(squared(pos, center)) * 10.0D) / 10.0D);
            fact.addProperty("relative_sector", sector(pos.getX() - center.getX(), pos.getZ() - center.getZ()));
            result.add(fact);
        });
        return result;
    }

    private static String semanticName(Entity entity) {
        return entity instanceof Player other
                ? other.getGameProfile().getName()
                : entity.getName().getString();
    }

    private static BlockPos semanticCenter(Goal goal, LocalPlayer player, IntentRuntime runtime) {
        Goal.SemanticTarget target = goal.target();
        if (target == null) return player.blockPosition();
        Goal.WorldPosition position = target.position();
        if (sameDimension(position, player)) return new BlockPos(position.x(), position.y(), position.z());
        String kind = lower(target.kind());
        if (("landmark".equals(kind) || "area".equals(kind)) && target.label() != null) {
            IntentRuntime.Landmark landmark = runtime.landmark(target.label());
            if (landmark != null && sameDimension(landmark.position(), player)) {
                Goal.WorldPosition at = landmark.position();
                return new BlockPos(at.x(), at.y(), at.z());
            }
        }
        return player.blockPosition();
    }

    private static BlockPos interactionStand(ClientLevel level, BlockPos target, BlockPos current) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int radius = 1; radius <= 3; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    for (int dy = -2; dy <= 1; dy++) {
                        BlockPos feet = target.offset(dx, dy, dz);
                        if (isStandable(level, feet)
                                && Vec3.atCenterOf(feet).distanceToSqr(Vec3.atCenterOf(target)) <= 4.5D * 4.5D) {
                            candidates.add(feet.immutable());
                        }
                    }
                }
            }
        }
        return candidates.stream().min(Comparator.comparingLong(pos -> squared(pos, current))).orElse(null);
    }

    private static boolean isStandable(ClientLevel level, BlockPos feet) {
        if (!level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.above()).isEmpty()) return false;
        if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()) return false;
        if (!level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) return false;
        BlockPos support = feet.below();
        return level.getBlockState(support).isFaceSturdy(level, support, Direction.UP);
    }

    private static String blockId(Goal goal, JsonObject p) {
        String value = string(p, "block_id");
        if (value != null || goal.target() == null) return value;
        String kind = lower(goal.target().kind());
        return List.of("block", "container", "block_type").contains(kind) ? goal.target().label() : null;
    }

    private static String itemId(JsonObject p) {
        return string(p, "item_id");
    }

    private static IntentAction requireInventoryItem(Goal goal, LocalPlayer player, String itemId) {
        if (itemId == null) return null;
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return invalidItem(goal, itemId);
        return firstStack(player, BuiltInRegistries.ITEM.get(id)) == null ? missingItem(goal, itemId) : null;
    }

    private static IntentAction invalidItem(Goal goal, String itemId) {
        return decision(goal, "Unknown item id: " + itemId,
                List.of(option("retry", "Retry with a valid namespaced item id."),
                        option("cancel", "Cancel this action.")), null);
    }

    private static IntentAction missingItem(Goal goal, String itemId) {
        JsonObject facts = new JsonObject();
        facts.addProperty("missing_item_id", itemId);
        return decision(goal, itemId + " is not in the inventory.",
                List.of(option("recover", "Acquire the item, then retry this action."),
                        option("replace_goal", "Choose an item that is already available."),
                        option("cancel", "Cancel this action.")), facts);
    }

    private static ItemStack firstStack(LocalPlayer player, Item item) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(item)) return stack;
        }
        return null;
    }

    private static int countItem(LocalPlayer player, Item item) {
        int count = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static List<FoodChoice> foodChoices(LocalPlayer player) {
