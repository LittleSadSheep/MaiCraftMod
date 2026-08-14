// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.LinkedHashSet;
import java.util.Set;

/** Public contracts that teach a model semantic fields without exposing internal actions. */
public final class SemanticAbilityCatalog {

    private SemanticAbilityCatalog() {}

    public static JsonObject describe(String ability) {
        return switch (ability) {
            case GeneralAbilityAdapter.FIND_ENTITY -> contract(
                    "Find real entities through loaded client evidence and bounded first-person frontier exploration; MaiCraft owns every route and concrete identity.",
                    targets("entity", "nearest", "area", "landmark", "current_place"),
                    fields(
                            field("entity_type_id", "resource_id", "One acceptable registered entity type; never a runtime entity ID."),
                            field("entity_type_ids", "array<resource_id>", "Acceptable registered entity types; never runtime entity IDs."),
                            field("relation", "string", "Wild, hostile, unowned or any. Wild/unowned fail closed on ownership and enclosure ambiguity."),
                            field("count", "integer", "Required distinct observed count; a partial count is not success."),
                            field("max_distance", "integer", "Bounded physical search distance from start; default 512, maximum 2048."),
                            field("may_alter_terrain", "boolean", "Hard consent for route digging, bridging or pillaring; default false."),
                            field("protected_labels", "array<string>", "Remembered areas or possessions that matching evidence must not use.")));
            case GeneralAbilityAdapter.COMBAT -> contract(
                    "Defend against or engage semantic living targets visible in loaded terrain; MaiCraft resolves concrete entities and combat movement.",
                    targets("entity", "player", "nearest"),
                    fields(
                            field("mode", "string", "Defend, engage or defeat; defend may select an immediate hostile threat."),
                            field("entity_type_id", "resource_id", "Optional namespaced entity type, never a runtime entity identifier."),
                            field("entity_name", "string", "Optional visible custom/display name."),
                            field("player_name", "string", "Optional exact player name."),
                            field("selection", "string", "Use nearest only when any matching loaded target is acceptable."),
                            field("count", "integer", "Maximum number of matching semantic targets."),
                            field("radius", "integer", "Bounded loaded-world search radius."),
                            field("allow_harm", "boolean", "Required explicit consent because combat can harm or kill."),
                            field("confirm_risky_target", "boolean", "Second confirmation for players, tame/named or non-hostile targets.")));
            case GeneralAbilityAdapter.INTERACT -> contract(
                    "Use one semantic block or entity; MaiCraft resolves the loaded target, approaches it and performs the ordinary interaction.",
                    targets("coordinates", "entity", "player", "nearest", "landmark", "area"),
                    fields(
                            field("block_id", "resource_id", "Optional namespaced block type to use."),
                            field("entity_type_id", "resource_id", "Optional namespaced entity type, never a runtime entity identifier."),
                            field("entity_name", "string", "Optional visible custom/display name."),
                            field("player_name", "string", "Optional exact player name."),
                            field("item_id", "resource_id", "Optional carried item whose ordinary use is intended."),
                            field("purpose", "string", "Non-destructive intent such as open, talk, trade or use."),
                            field("selection", "string", "Nearest means any nearest loaded semantic match is acceptable."),
                            field("radius", "integer", "Bounded loaded-world search radius."),
                            field("may_alter_terrain", "boolean", "Explicit route permission; default false.")));
            case GeneralAbilityAdapter.FOLLOW -> contract(
                    "Follow one semantic player or entity while MaiCraft continuously resolves movement.",
                    targets("player", "entity", "nearest"),
                    fields(
                            field("entity_type_id", "resource_id", "Optional namespaced entity type, never a runtime entity identifier."),
                            field("entity_name", "string", "Optional visible custom/display name."),
                            field("player_name", "string", "Optional exact player name."),
                            field("selection", "string", "Nearest means any nearest loaded match is acceptable."),
                            field("distance", "integer", "Preferred following distance in blocks, not a route."),
                            field("radius", "integer", "Initial bounded loaded-world search radius."),
                            field("may_alter_terrain", "boolean", "Explicit route permission; default false.")));
            case GeneralAbilityAdapter.CONSUME -> contract(
                    "Eat a suitable carried food until the semantic hunger outcome is addressed; MaiCraft chooses the inventory entry and timed use.",
                    targets("current_place"),
                    fields(
                            field("item_id", "resource_id", "Optional exact carried food; omit to choose safe effect-free food."),
                            field("allow_effects", "boolean", "Explicit consent to consume food with effects; default false.")));
            case GeneralAbilityAdapter.EQUIP -> contract(
                    "Equip or unequip semantic gear; MaiCraft resolves the concrete inventory entry.",
                    targets("current_place"),
                    fields(
                            field("action", "string", "Equip or unequip."),
                            field("item_id", "resource_id", "Optional exact carried item; omit only when one compatible choice exists."),
                            field("equipment_location", "string", "Semantic body location: mainhand, offhand, head, chest, legs, feet or armor; never a GUI inventory index.")));
            case GeneralAbilityAdapter.FISH -> contract(
                    "Fish with a carried rod using normal first-person casting and retrieval.",
                    targets("current_place", "area", "landmark"),
                    fields(field("count", "integer", "Number of catches requested, not casts or clicks.")));
            case GeneralAbilityAdapter.DROP -> contract(
                    "Irreversibly drop an explicit quantity of one carried item.",
                    targets("current_place"),
                    fields(
                            field("item_id", "resource_id", "Exact carried item to drop."),
                            field("count", "integer", "Explicit quantity to drop; required because the action is irreversible.")));
            case GeneralAbilityAdapter.CONTAINER -> contract(
                    "Open or ordinarily use one semantic loaded block container; this ability does not transfer contents.",
                    targets("coordinates", "nearest", "landmark", "area"),
                    fields(
                            field("block_id", "resource_id", "Namespaced container block type."),
                            field("selection", "string", "Nearest means any nearest loaded semantic match is acceptable."),
                            field("purpose", "string", "Open, inspect or ordinary non-destructive use."),
                            field("radius", "integer", "Bounded loaded-world search radius."),
                            field("may_alter_terrain", "boolean", "Explicit route permission; default false.")));
            case GeneralAbilityAdapter.MANAGE_CONTAINER -> contract(
                    "Deposit, withdraw or balance a semantic item group against one real loaded block container. MaiCraft selects and approaches it, opens the native menu, derives safe sides, transfers and verifies both inventories.",
                    targets("nearest", "landmark", "area"),
                    fields(
                            field("operation", "string", "Deposit, withdraw or balance."),
                            field("item_id", "resource_id", "One selected item; use item_ids for a loot category."),
                            field("item_ids", "array<resource_id>", "A semantic item group to transfer together."),
                            field("tag", "resource_id", "An item tag alternative to item_id/item_ids."),
                            field("count", "integer", "Exact total amount to move; omit to move all matching source items."),
                            field("target_count", "integer", "Desired final count on the semantic destination side; balance uses the main inventory."),
                            field("block_id", "resource_id", "Optional container block-type filter, never a location."),
                            field("selection", "string", "Nearest accepts the closest safe match; unique rejects ambiguity."),
                            field("protected_labels", "array<string>", "Remembered places whose containers must not be touched."),
                            field("radius", "integer", "Bounded loaded-container search radius; default 32.")));
            case "maicraft:sleep" -> contract(
                    "Sleep safely. MaiCraft finds or places a usable bed, travels to it and lies down.",
                    targets("current_place"),
                    fields());
            case "maicraft:remember_place" -> contract(
                    "Remember a meaningful place under a human label.",
                    targets("current_place", "coordinates", "landmark", "area"),
                    fields(field("label", "string", "The durable human name for the place.")));
            case "maicraft:travel" -> contract(
                    "Reach a semantic destination; MaiCraft resolves and follows the route.",
                    targets("coordinates", "landmark", "player", "entity", "nearest", "area", "prior_result"),
                    fields(
                            field("exact", "boolean", "Whether the exact Y cell matters."),
                            field("semantic_target", "string", "Coast, biome id or #biome_tag when the destination must be discovered."),
                            field("biome_id", "resource_id", "Optional exact biome to discover."),
                            field("biome_tag", "resource_id", "Optional biome tag to discover."),
                            field("max_distance", "integer", "Bounded exploration radius; omit for the Mod default."),
                            field("may_alter_terrain", "boolean", "Hard consent to dig, bridge or pillar; default false.")));
            case "maicraft:travel_dimension" -> contract(
                    "Reach another dimension through a real portal. MaiCraft discovers the loaded portal, walks into it, survives the LocalPlayer replacement and verifies the destination.",
                    targets("current_place", "landmark", "area", "prior_result"),
                    fields(
                            field("destination_dimension", "resource_id", "Required destination dimension, such as minecraft:the_nether or minecraft:the_end."),
                            field("max_search_radius", "integer", "Bounded loaded-world portal evidence radius; default 128."),
                            field("may_alter_terrain", "boolean", "Hard consent for route digging, bridging or pillaring; default false.")));
            case "maicraft:find_structure" -> contract(
                    "Discover and optionally reach a structure through physical first-person evidence. Strongholds use real ender-eye throws; other registered structures use bounded loaded-world evidence profiles.",
                    targets("current_place", "area", "landmark", "prior_result"),
                    fields(
                            field("structure_id", "resource_id", "Required structure identity, such as minecraft:stronghold or minecraft:fortress."),
                            field("max_distance", "integer", "Maximum physical search distance from the starting region; default and maximum 4096."),
                            field("reach_structure", "boolean", "Whether to physically reach and re-verify the observed structure; default true."),
                            field("may_alter_terrain", "boolean", "Hard consent for route digging, bridging or pillaring; default false."),
                            field("allow_rare_consumables", "boolean", "Explicitly permits real ender-eye throws when structure_id is minecraft:stronghold; default false.")));
            case "maicraft:reach_milestone" -> contract(
                    "Reach one survival milestone through a recoverable, live-fact-driven internal progression state machine. MaiCraft derives prerequisites and concrete work; it never builds, repairs or activates portals.",
                    targets("current_place", "area", "prior_result"),
                    fields(
                            field("milestone", "string", "Nether, stronghold, defeat_dragon or elytra."),
                            field("max_search_distance", "integer", "Bounded physical structure and End search distance; maximum 4096."),
                            field("max_portal_search_radius", "integer", "Bounded loaded active-portal evidence radius; default 128."),
                            field("minimum_health", "number", "Health floor for a separately permitted boss encounter; default 10."),
                            field("allow_combat", "boolean", "Separate consent for hostile combat; never inferred from terrain permission."),
                            field("allow_rare_consumables", "boolean", "Separate consent for typed rare resource use such as eyes or gateway pearls."),
                            field("may_alter_terrain", "boolean", "Ordinary route/mining permission only; never permission to create, repair or activate a portal."),
                            field("allowed_sources", "array<string>", "Permitted semantic prerequisite sources; MaiCraft chooses no slots, routes or concrete targets here."),
                            field("material_policy", "string", "Ordinary, storage_available or inventory_only prerequisite supply."),
                            field("protected_labels", "array<string>", "Remembered areas, entities or possessions all child work must preserve.")));
            case "maicraft:defeat_ender_dragon" -> contract(
                    "Resolve the currently observed vanilla Ender Dragon encounter. MaiCraft handles crystal order, cages, hazard evasion, recovery and death verification.",
                    targets("current_place", "area", "prior_result"),
                    fields(
                            field("allow_combat", "boolean", "Required explicit consent to destroy crystals and kill the dragon."),
                            field("may_alter_terrain", "boolean", "May open a freshly verified iron-bar crystal cage; default false."),
                            field("minimum_health", "number", "Health floor below which MaiCraft disengages and recovers; default 10."),
                            field("protected_labels", "array<string>", "Remembered places or possessions that must not be altered.")));
            case "maicraft:obtain_elytra" -> contract(
                    "Bring one real elytra into the main inventory after the dragon fight. MaiCraft resolves the gateway, pearl use, End City and ship search, item frame and collection.",
                    targets("current_place", "area", "prior_result"),
                    fields(
                            field("max_search_distance", "integer", "Bounded physical End City search distance; default 2048, maximum 4096."),
                            field("may_alter_terrain", "boolean", "Hard consent for route bridging, pillaring or clearing; default false."),
                            field("allow_combat", "boolean", "May handle only loaded hostiles actively targeting the player and blocking progress; default false."),
                            field("allow_rare_consumables", "boolean", "Explicitly permits the real ender-pearl use required for End Gateway traversal; default false."),
                            field("protected_labels", "array<string>", "Remembered areas or possessions that must not be touched.")));
            case "maicraft:craft" -> contract(
                    "Craft an item. Recipe choice, GUI slots and intermediate ingredients belong to MaiCraft.",
                    targets("nearest", "prior_result"),
                    fields(
                            field("item_id", "resource_id", "Requested output."),
                            field("count", "integer", "Requested final inventory count, not number of clicks.")));
            case "maicraft:cook" -> contract(
                    "Cook an item through an ordinary furnace-family workstation. Recipe, input, fuel quantity, workstation, path and synchronized GUI transactions belong to MaiCraft.",
                    targets("nearest", "prior_result"),
                    fields(
                            field("item_id", "resource_id", "Requested cooked output."),
                            field("count", "integer", "Required final main-inventory count, not operations."),
                            field("recipe_preference", "string", "Auto, fastest, preserve_rare, smelting, blasting, smoking or campfire; campfire currently returns a structured unsupported decision."),
                            field("allowed_fuels", "array<resource_id>", "Optional fuel policy; omit for a conservative ordinary-fuel set."),
                            field("allowed_sources", "array<string>", "Where MaiCraft may obtain recipe input, fuel and a required workstation; recursive cook edges are removed to prevent cycles."),
                            field("allow_harm", "boolean", "Whether recursively acquiring inputs may harm living entities; default false."),
                            field("protected_labels", "array<string>", "Remembered places or possessions recursive acquisition must not touch.")));
            case "maicraft:trade" -> contract(
                    "Obtain an item from a real loaded merchant. MaiCraft selects the merchant and offer, approaches in first person, performs synchronized payment/result transfers and verifies the final inventory.",
                    targets("nearest", "area", "landmark", "prior_result"),
                    fields(
                            field("item_id", "resource_id", "Requested trade output."),
                            field("count", "integer", "Required final inventory count, not number of trades."),
                            field("merchant_kind", "string", "Auto, villager or wandering_trader."),
                            field("allowed_payment_items", "array<resource_id>", "Hard policy for what may be spent; when omitted, only emerald payments are automatic and other observed candidates require a decision."),
                            field("protected_labels", "array<string>", "Remembered places whose merchants must not be selected."),
                            field("radius", "integer", "Bounded loaded-merchant search radius; default 32.")));
            case "maicraft:acquire_items" -> contract(
                    "Make requested inventory facts true using allowed sources, stopping as soon as they are true.",
                    targets("nearest", "area", "landmark", "prior_result"),
                    fields(
                            field("item_id", "resource_id", "One requested item; item_ids may express alternatives."),
                            field("item_ids", "array<resource_id>", "Acceptable alternatives, not an ordered recipe."),
                            field("item_tag", "resource_id", "A semantic item tag such as minecraft:beds or minecraft:planks; the Mod resolves live members."),
                            field("item_tags", "array<resource_id>", "Several semantic item tags combined as acceptable alternatives."),
                            field("count", "integer", "Required final count."),
                            field("allowed_sources", "array<string>", "Inventory, nearby, storage, craft, cook, mine, trade or hunt."),
                            field("allow_harm", "boolean", "Whether acquiring may harm living entities; default false."),
                            field("protected_labels", "array<string>", "Named entities, areas or possessions that must not be touched."),
                            field("radius", "integer", "Optional bounded loaded-world evidence radius."),
                            field("source_hint", "object", "Optional semantic source evidence: block/tag/entity/trade families and expected products; never coordinates, routes, entity IDs, slots or clicks.")));
            case "maicraft:build" -> contract(
                    "Design, site and construct one bounded structure. MaiCraft chooses cells and build order.",
                    targets("area", "landmark", "coordinates", "current_place", "prior_result"),
                    fields(
                            field("purpose", "string", "What the structure is for, such as seaside_house."),
                            field("size", "string|object", "Small/medium/large or optional approximate bounds."),
                            field("style", "string", "Visual language; leave open to give MaiCraft design freedom."),
                            field("features", "array<string>", "Semantic features such as dock, porch, cellar or workshop."),
                            field("terrain_fit", "string", "Surface, embedded, cave, hillside or underground."),
                            field("material_policy", "string", "Available, storage_available, specified or preserve_rare."),
                            field("preferred_materials", "array<resource_id>", "Palette preferences, never per-cell assignments."),
                            field("replace_existing", "boolean", "Explicit permission to replace occupied cells; default false.")));
            case "maicraft:light_area" -> contract(
                    "Observe, construct and verify actual block-light coverage over a bounded loaded semantic area.",
                    targets("area", "landmark", "coordinates", "current_place", "prior_result"),
                    fields(
                            field("radius", "integer", "Bounded area radius; default 16."),
                            field("minimum_light", "integer", "Required observed block-light threshold, 1-15."),
                            field("coverage", "string", "All, most, crop_growth or player_visibility; defines which observed cells count."),
                            field("block_id", "resource_id", "Optional preferred light-source item/block, never a placement instruction."),
                            field("light_preferences", "array<resource_id>", "Ordered aesthetic source preferences; carried alternatives remain usable."),
                            field("material_policy", "string", "Ordinary, storage_available or inventory_only; storage is inspected before automatic crafting."),
                            field("allowed_sources", "array<string>", "Permitted semantic supply sources; never slots, routes or cells."),
                            field("allow_harm", "boolean", "Explicit harmful acquisition permission; false unless the player grants it."),
                            field("protected_labels", "array<string>", "Remembered areas/possessions that placement must not touch."),
                            field("style", "string", "Auto, ground, wall, hanging or unobtrusive; MaiCraft still chooses cells."),
                            field("placement_preference", "string", "Safe-candidate tie-break after measured coverage gain: coverage_optimal (default), central_unplanted (crop_growth only) or unobtrusive; MaiCraft still chooses cells.")));
            case "maicraft:connect_mechanical_power" -> contract(
                    "Connect two semantic mechanical networks while respecting axes, stress and protected terrain.",
                    targets("landmark", "area", "prior_result"),
                    fields(
                            field("source_label", "string", "Existing powered network or landmark."),
                            field("target_label", "string", "Destination machine, structure or landmark."),
                            field("transmission", "string", "Requested family such as chain_drive."),
                            field("allow_new_receiver", "boolean", "May terminate at a verified empty receiver cell when no destination machine exists."),
                            field("material_policy", "string", "Ordinary, storage_available or inventory_only; applied after route investigation."),
                            field("allowed_sources", "array<string>", "Permitted semantic material sources; storage is tried before crafting."),
                            field("allow_harm", "boolean", "Explicit harmful acquisition permission; never inferred."),
                            field("protected_labels", "array<string>", "Remembered resources or areas that material acquisition must preserve.")));
            case "maicraft:wait_for_condition" -> contract(
                    "Wait without inventing body work until an observable condition is true.",
                    targets("current_place"),
                    fields(
                            field("condition", "string", "Elapsed, day, night, health_full or not_hungry."),
                            field("after_s", "integer", "Minimum elapsed seconds when relevant.")));
            case "maicraft:sequence" -> contract(
                    "Run semantic child goals in order; each child remains independently observable and recoverable.",
                    targets(),
                    fields());
            default -> contract(
                    "Unknown semantic ability.",
                    targets(),
                    fields());
        };
    }

    private static JsonObject contract(
            String summary, JsonArray targets, JsonObject parameters) {
        JsonObject result = new JsonObject();
        result.addProperty("summary", summary);
        result.add("accepted_target_kinds", targets);
        result.add("parameters", parameters);
        result.add("accepted_preferences", fields());
        result.add("accepted_hard_constraints", targets());
        result.addProperty(
                "execution_boundary",
                "MaiCraft selects routes, coordinates, placements, inventory slots and retries; the LLM does not.");
        return result;
    }

    static Set<String> parameterNames(String ability) {
        return names(describe(ability).getAsJsonObject("parameters"));
    }

    static Set<String> preferenceNames(String ability) {
        return names(describe(ability).getAsJsonObject("accepted_preferences"));
    }

    static Set<String> targetKinds(String ability) {
        JsonArray values = describe(ability).getAsJsonArray("accepted_target_kinds");
        LinkedHashSet<String> result = new LinkedHashSet<>();
        values.forEach(value -> result.add(value.getAsString()));
        return Set.copyOf(result);
    }

    static Set<String> hardConstraintKinds(String ability) {
        JsonArray values = describe(ability).getAsJsonArray("accepted_hard_constraints");
        LinkedHashSet<String> result = new LinkedHashSet<>();
        values.forEach(value -> result.add(value.getAsString()));
        return Set.copyOf(result);
    }

    private static Set<String> names(JsonObject fields) {
        return fields == null ? Set.of() : Set.copyOf(fields.keySet());
    }

    private static JsonArray targets(String... values) {
        JsonArray result = new JsonArray();
        for (String value : values) result.add(value);
        return result;
    }

    private static JsonObject fields(JsonObject... fields) {
        JsonObject result = new JsonObject();
        for (JsonObject field : fields) {
            String name = field.remove("name").getAsString();
            result.add(name, field);
        }
        return result;
    }

    private static JsonObject field(String name, String type, String description) {
        JsonObject result = new JsonObject();
        result.addProperty("name", name);
        result.addProperty("type", type);
        result.addProperty("description", description);
        return result;
    }
}
