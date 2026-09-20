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
        JsonObject description = describeContract(ability);
        if (MachineAbilityAdapter.DESIGN.equals(ability) || MachineAbilityAdapter.BUILD.equals(ability)
                || MachineAbilityAdapter.MODIFY.equals(ability)) {
            var budget = org.maiwithu.maicraft.core.integration.machine.MachinePlanningBudget.current();
            JsonObject limits = new JsonObject();
            limits.addProperty("max_targets", budget.maxTargets()); limits.addProperty("max_components", budget.maxComponents());
            limits.addProperty("max_connections", budget.maxConnections()); limits.addProperty("max_radius", budget.maxRadius());
            limits.addProperty("search_visited_budget", budget.searchVisitedBudget());
            limits.addProperty("configuration", "Owner-adjustable JVM properties: maicraft.machine.planning.*");
            description.add("planning_budget", limits);
        }
        // 保存、预览和实际建造都公布当前建筑配置，不把机器规划默认值误当成整座建筑上限。
        if ("maicraft:build".equals(ability) || BuildDesignAdapter.ABILITY.equals(ability))
            description.add("planning_budget", org.maiwithu.maicraft.core.build.BuildingBudgetReport.current());
        return description;
    }

    private static JsonObject describeContract(String ability) {
        return switch (ability) {
            case ChatAbilityAdapter.ABILITY -> contract(
                    "Open the real game chat box, visibly type one complete message or slash-prefixed command, then submit it once through native chat handling. Uses the current player's permissions and loader command hooks. No foreground window or keyboard simulation is required. Existing human chat, containers and manual pause menus are preserved; an invisible background focus-loss pause may be replaced. Human input/Esc cancels automation, and task pause retains the draft. Success means submitted_to_client, not confirmed server delivery or command execution. Inspect Attention for responses; reuse one execute request_key for transport retries and never resend an uncertain submission automatically.",
                    targets(), fields(
                            field("text", "string", "Required single line, 1-256 UTF-16 characters. A leading / submits a command; otherwise sends public player chat. Uses vanilla whitespace normalization. No control characters or section-sign formatting; Chinese and complete Unicode graphemes are supported."),
                            field("typing_interval_ms", "integer", "Time between displayed characters, 50-1000 ms, default 100. A slow client may take longer; it never bursts to catch up. The completed draft remains visible for 250 ms before automatic submission.")));
            case MachineAbilityAdapter.INSPECT -> contract(
                    "Inspect a machine or an observed physical structure. Includes native control components, ordered wireless frequencies, directed redstone ports, kinetic connections and control-to-actuator paths. A seat or physical body alone does not establish a vehicle. World surveys retain relative layout and snapshot_id; structure_id surveys use moving native block coordinates and do not create a fixed-place snapshot. Unknown circuits and force/stability analysis remain explicit.",
                    targets("current_place", "coordinates", "landmark", "area", "prior_result"),
                    fields(
                            field("label", "string", "Short durable machine label; required unless the target already supplies one. Stored as a landmark; observing grants no mutation authority."),
                            field("radius", "integer", "World survey cube radius 0-8, default 4. Unloaded or truncated evidence remains unknown."),
                            field("component_offset", "integer", "Continue optional server component observations from the previous server_evidence.next_component_index, 0-768. Each inspection retains its own snapshot identity/tick; pages are not one atomic world snapshot. Omit with structure_id."),
                            field("resource_offset", "integer", "Continue the starting component's resources from server_evidence.next_resource_offset, 0-4096, together with its next_component_index. Later components start at zero. Omit with structure_id."),
                            field("structure_id", "string", "Observed physical-structure UUID. Inspects its native blocks and control circuits; omit target and radius. Does not assume the structure can be driven.")));
            case MachineAbilityAdapter.DESIGN -> contract(
                    "Review a machine layout against installed block/item IDs without construction. Supply exactly one of design, blueprint or blueprint_uri. A semantic design lets MaiCraft arrange components; a blueprint declares the model's exact structure, using the same JSON as exported Ponder chapters. Reports materials and unresolved requirements. expected_output in a semantic design queries synchronized recipes; successful review does not prove construction or production.",
                    targets("landmark", "area"),
                    fields(field("design", "object",
                            "Shape: {components:[{name,block_id,count,role,module?,module_tier?,module_options?}],connections:[{from,to,medium,purpose}],expected_output?:item_id,style?:string,constraints?:{max_width?,max_depth?,max_height?,terrain_fit?,maintenance_access?,preserve_existing?,throughput?}}. Planning budgets: 1024 component groups, 4096 connections, 32768 physical targets. Modules: create:press_station, create:press_basin_station, create:mixer_station, ae2:storage_cluster, ae2:crafting_cluster, mekanism:induction_matrix. Matrix options: width/height/depth/cell_count/provider_count, tier basic/advanced/elite/ultimate. AE options: storage_tier and storage_cells. Media include kinetic, items, fluids, energy, chemicals and ae_network; unsupported interfaces produce specific compiler issues. " + utilityInputs(false)),
                            blueprintField(), blueprintUriField(), productionField(),
                            field("snapshot_id", "string", "Optional fresh site/machine observation that grounds site-specific analysis; requires the exact surveyed target label. Blueprint offsets are relative to this anchor.")));
            case MachineAbilityAdapter.BUILD -> contract(
                    "Construct a machine at an observed anchor using design, blueprint or blueprint_uri. MaiCraft supplies construction materials and verifies native placement. With production and allow_use=true, then run the same v1 network or v2 native process; v1 requires server production evidence, v2 uses its mechanism's requirements. Dev mode waits for local preview confirmation.",
                    targets("landmark", "area"),
                    fields(
                            field("snapshot_id", "string", "Fresh complete inspect_machine receipt identifying the build anchor. The complete construction footprint is subsequently inspected by the native task and is not limited to the anchor survey radius."),
                            field("design", "object", "Alternative to blueprint/blueprint_uri: same semantic graph as design_machine, including external_inputs and supply_preference. " + utilityInputs(false)),
                            blueprintField(), blueprintUriField(), productionField(),
                            field("allow_modify", "boolean", "Set true when the player's instructions authorize construction at this site."),
                            field("allow_use", "boolean", "Required true with production: authorizes operating the declared machine after construction. Omit when production is absent."),
                            field("material_policy", "string", "ordinary, storage_available (including an existing AE2 network), or inventory_only; exact machine items are never substituted."),
                            field("replace_existing", "boolean", "Allow removing ordinary obstructing blocks in the compiled footprint; block entities stay protected unless replace_block_entities is also true. Default false."),
                            field("replace_block_entities", "boolean", "Also allow replacing existing block entities at declared targets; requires replace_existing=true and authorization for those changes. Default false."),
                            field("protected_labels", "array<string>", "Remembered areas that construction and material acquisition must preserve.")));
            case MachineAbilityAdapter.OPERATE -> contract(
                    "Use surveyed machines through native evidence. run_production accepts a v1 production network or a v2 native process; inspect_machine supplies matching mechanism contracts. Existing menu transfers, controls and AE2 supply retain their own evidence requirements. Success reports verified effects.",
                    targets("landmark", "area", "nearest", "coordinates", "prior_result"),
                    fields(
                            field("operation", "string", "run_production, watch_production, cancel_watch, drive_vehicle, open_menu, close_menu, deposit, withdraw, set_control or ae2_supply. run_production executes v1 networks or finite v2 processes. watch_production registers a v1 server read-only monitor before a future batch; registration is not completion, and it releases the body without refills or forced chunk loads. cancel_watch stops monitoring only. close_menu/cancel_watch omit target."),
                            productionField(),
                            field("job_id", "string", "cancel_watch only: exact job_id from monitor registration or perceive(machines). Monitors belong to the current player connection and dimension; reconnect requires new registration."),
                            field("minimum_process_events", "integer", "watch_production only: required native completions per process, 1-100, default 1. Use a small repeated sample for commissioning; exact requested target output still comes from production.observation.minimum_output."),
                            field("idle_ticks", "integer", "watch_production only: loaded time with no progress before Attention requests inspection; 20..max_duration_ticks, default the smaller of 6000 and max_duration_ticks. Unloaded machines remain unknown and do not accrue inactivity."),
                            field("max_duration_ticks", "integer", "watch_production only: finite monitor duration 20-72000 ticks, default 72000. It does not keep the chunk loaded or survive a connection/world change."),
                            field("protected_labels", "array<string>", "run_production only: remembered areas that navigation and material acquisition must preserve."),
                            field("material_policy", "string", "run_production: v1 configuration-tool acquisition policy; v2 accepts inventory_only and uses carried inputs. Acquire missing inputs separately. build_machine uses its existing construction supply policy."),
                            field("structure_id", "string", "drive_vehicle only: observed physical-structure UUID. target is the destination. Existing control bindings are preserved; no force/stability model is assumed."),
                            field("allow_use", "boolean", "Required true only when the player's instructions authorize this use of the machine/network; never infer ownership from a label."),
                            field("snapshot_id", "string", "run_production/watch_production/set_control/open_menu: fresh inspect_machine receipt for the exact target label; consumed by operation."),
                            field("component_index", "integer", "open_menu only: index of the desired block in snapshot.relative_blocks; omit to use the marked center. Must come from that exact observation."),
                            field("menu_receipt_id", "string", "deposit/withdraw only: receipt from perceive(view=machine_menu) after open_menu. Omit target; the receipt already binds the exact native menu. Inspect again after each transaction."),
                            field("entry_index", "integer", "deposit/withdraw only: exact observed native menu entry. MaiCraft chooses player inventory entries, validates slot rules and never quick-moves or swaps arbitrary contents."),
                            field("powered", "boolean", "set_control only: desired lever state, required. This is control state, not measured production."),
                            field("control_label", "string", "set_control only: remembered exact vanilla lever within the surveyed region; omit only when exactly one lever is present."),
                            field("item_id", "resource_id", "ae2_supply/deposit/withdraw: exact requested registered item."),
                            field("count", "integer", "Exact item quantity, default 1: transfer 1-64, AE2 approved net increase 1-256. Use a stable execute request_key to prevent uncertain transport retries creating duplicate work."),
                            field("allow_crafting", "boolean", "ae2_supply only: may submit an existing AE2 crafting pattern, default false. Requires target={kind:nearest} without a label; selecting a specific surveyed network is unsupported.")));
            case MachineAbilityAdapter.MODIFY -> contract(
                    "Modify an existing surveyed machine. apply_blueprint applies explicit desired blocks at anchor-relative offsets and preserves omitted positions; exactly one blueprint or blueprint_uri is required. connect_mechanical_power lets MaiCraft plan a Create rotational route. Success verifies the requested structural change; running and production are checked separately through use abilities.",
                    targets("landmark", "area"),
                    fields(
                            field("operation", "string", "apply_blueprint, connect_mechanical_power or connect_external_input. External utility hookup is a separate task after construction; inspect the machine and choose a remembered city source."),
                            field("snapshot_id", "string", "Fresh complete receipt for the exact destination machine label; consumed before execution. Resurvey before another attempt."),
                            field("source_label", "string", "Remembered existing outlet in the same dimension. For a kinetic connect_external_input, omit to compare nearby loaded powered sources; an explicit source remains fixed. Required for other utility media and connect_mechanical_power."),
                            field("source_radius", "integer", "Kinetic connect_external_input automatic source search radius, 8..128 blocks, default 64. Never loads distant chunks or walks solely to scan."),
                            field("input_id", "string", "connect_external_input only: exact external input id from perceive(machines). Create compares native interfaces and full transmission costs; FE/Mek cable hookup requires server assistance. Other media reject before mutation."),
                            blueprintField(), blueprintUriField(),
                            field("material_policy", "string", "apply_blueprint/connect_external_input: ordinary, storage_available or inventory_only."),
                            field("replace_existing", "boolean", "apply_blueprint only: allow replacing ordinary obstructing blocks, default false. Declare minecraft:air to request removal at an explicit offset."),
                            field("replace_block_entities", "boolean", "apply_blueprint only: also allow replacing block entities at declared targets, default false; requires replace_existing=true and authorization for those changes."),
                            field("protected_labels", "array<string>", "apply_blueprint/connect_external_input: remembered areas that modification and material acquisition must preserve."),
                            field("allow_modify", "boolean", "Required true when the player's instructions authorize this change; existing authorization carries through the workflow.")));
            case GeneralAbilityAdapter.FIND_ENTITY -> contract(
                    "Find real entities through loaded client evidence and bounded first-person frontier exploration; MaiCraft owns every route and concrete identity.",
                    targets("entity", "nearest", "area", "landmark", "current_place"),
                    fields(
                            field("entity_type_id", "resource_id", "One acceptable registered entity type; never a runtime entity ID."),
                            field("entity_type_ids", "array<resource_id>", "Acceptable registered entity types; never runtime entity IDs."),
                            field("relation", "string", "Wild, hostile, unowned or any. Wild/unowned preserve named, tame, owned, leashed or vehicle-held entities; enclosure counts only inside an explicitly protected semantic area."),
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
                            field("purpose", "string", "Open, talk, trade, use or till. Till prepares a suitable hoe when item_id is omitted."),
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
                    fields(
                            field("label", "string", "The durable human name for the place."),
                            field("area_role", "ordinary|managed_settlement",
                                    "Optional typed policy, default ordinary. Use managed_settlement only when the player explicitly identifies this area as a managed base, city or settlement; labels themselves never imply this role.")));
            case "maicraft:travel" -> contract(
                    "Reach a destination area; ordinary travel accepts nearby reachable ground, while an explicit exact request requires one cell. Biome/coast discovery verifies the requested region rather than an invented precise point.",
                    targets("coordinates", "landmark", "player", "entity", "nearest", "area", "prior_result"),
                    fields(
                            field("destination", "object", "Travel-only coordinates {x,z,y?,dimension?}; omit target and other destination fields. Omit y only when height is unknown. Supplied y remains a height hint. Existing coordinates targets still require all three axes."),
                            field("structure_id", "string", "Observed physical-structure UUID to board using an equipped Create jetpack. Omit target/destination/discovery fields; transport_mode must be auto or jetpack. MaiCraft selects a native deck face, follows its changing pose and confirms actual support on that vessel."),
                            field("elevator_id", "string", "Optional observed elevator UUID from surroundings.elevators. Omit to select the nearest loaded elevator; use with elevator_floor and no coordinate destination."),
                            field("elevator_floor", "string", "ask (default for unlocated elevator travel), top, bottom, next_up, next_down, or an exact synchronized floor id/name. ask approaches the elevator, synchronizes its floors, then returns an LLM decision. Explicit floors synchronize first when needed. Use transport_mode=auto/elevator; do not guess a height."),
                            field("exact", "boolean", "Default false. True requires the exact x/y/z cell and a supplied or resolved height; use only when precise standing position matters."),
                            field("horizontal_radius", "number", "Nonnegative arrival radius in X/Z blocks; default 3 for ordinary travel. Ignored when exact=true."),
                            field("vertical_tolerance", "number", "Nonnegative allowed distance from a supplied Y hint; default 2. Omitted Y leaves elevation open. Ignored when exact=true."),
                            field("transport_mode", "auto|ground|jetpack|elevator", "Default auto selects available native travel. Platform discovery supports ground or continuous jetpack exploration without a preselected landing. Elevator accepts elevator_floor or a located destination; omitting both approaches and asks which synchronized floor to use. Undiscovered coast/biome goals support ground/auto exploration. This grants no terrain-alteration permission."),
                            field("allow_water_bucket_fall", "boolean", "Allow temporary bucket water for falls to a located destination, without granting digging or scaffold placement. Default false."),
                            field("allow_landing_assists", "boolean", "Allow verified temporary landing aids from carried items, including water and boats, without granting excavation or scaffolding. Requires a located destination; default false."),
                            field("semantic_target", "string", "Discover coast, a biome id/#biome_tag, or platform. Platform discovery keeps observing while moving; no coordinates are required. Use direction to choose where to search."),
                            field("direction", "down|up|forward|backward|left|right|north|south|east|west", "For platform discovery only; defaults to forward. Use down to find a platform below. Relative directions use the heading when travel begins. Regional radius max_distance is 8..128 (default 64)."),
                            field("biome_id", "resource_id", "Optional exact biome to discover."),
                            field("biome_tag", "resource_id", "Optional biome tag to discover."),
                            field("max_distance", "integer", "Bounded exploration radius; omit for the Mod default."),
                            field("may_alter_terrain", "boolean", "Hard consent to dig, bridge or pillar; default false."),
                            field("protected_labels", "array<string>", "Remembered areas whose previously measured footprint this movement must preserve.")));
            case "maicraft:travel_dimension" -> contract(
                    "Reach another dimension through a real portal. With prepare_portal, MaiCraft can prepare a Nether or End entry portal before walking through and verifying the new dimension.",
                    targets("current_place", "landmark", "area", "prior_result"),
                    fields(
                            field("destination_dimension", "resource_id", "Required destination dimension, such as minecraft:the_nether or minecraft:the_end."),
                            field("max_search_radius", "integer", "Bounded loaded-world portal evidence radius; default 128."),
                            field("prepare_portal", "boolean", "If no active portal is observed, obtain materials and prepare one; default false. Nether construction/repair also needs may_alter_terrain; End eyes need allow_rare_consumables."),
                            field("allow_rare_consumables", "boolean", "Permit stronghold eye throws and End frame eye insertion; default false."),
                            field("allow_combat", "boolean", "Permit hostile hunting for portal supplies; default false."),
                            field("max_search_distance", "integer", "Physical stronghold search limit during preparation; default and maximum 4096."),
                            field("allowed_sources", "array<string>", "Permitted material sources for portal preparation."),
                            field("material_policy", "string", "Ordinary, storage_available or inventory_only material supply."),
                            field("protected_labels", "array<string>", "Remembered places and inherited areas to preserve throughout preparation."),
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
                    "Reach one survival milestone through live-fact-driven progression. MaiCraft derives prerequisites and can prepare missing active portals when prepare_portal is enabled.",
                    targets("current_place", "area", "prior_result"),
                    fields(
                            field("milestone", "string", "Nether, stronghold, defeat_dragon or elytra."),
                            field("max_search_distance", "integer", "Bounded physical structure and End search distance; maximum 4096."),
                            field("max_portal_search_radius", "integer", "Bounded loaded active-portal evidence radius; default 128."),
                            field("prepare_portal", "boolean", "Enable Nether frame construction/repair and End frame activation when needed; default false."),
                            field("minimum_health", "number", "Health floor for a separately permitted boss encounter; default 10."),
                            field("allow_combat", "boolean", "Separate consent for hostile combat; never inferred from terrain permission."),
                            field("allow_rare_consumables", "boolean", "Separate consent for typed rare resource use such as eyes or gateway pearls."),
                            field("may_alter_terrain", "boolean", "Permit route/mining changes and Nether construction when prepare_portal is also enabled."),
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
                    "Craft an item. Recipe choice, intermediate ingredients, workstation approach or bounded crafting-table preparation, placement and GUI slots belong to MaiCraft.",
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
            // 向模型公开的是单件附魔意图、真实报价档位和成本；具体槽位与按钮由可见的原生界面执行器决定。
            case EnchantAbilityAdapter.ABILITY -> contract(
                    "Enchant exactly one carried compatible unenchanted item at an existing loaded vanilla enchanting table. "
                            + "Uses a visible native GUI, reads all three synchronized offers, submits the requested tier once, verifies actual enchantment and costs, then returns the result and remaining owned lapis. "
                            + "Books become enchanted books. No table construction, material acquisition, XP farming, reroll or exact hidden-enchantment guarantee. "
                            + "Requires explicit spend limits; the displayed required XP level differs from levels consumed. A durable reservation blocks automatic repeats after interruption or uncertain results; inspect before requesting a new operation.",
                    targets("coordinates","landmark","nearest"), fields(
                            field("item_id","resource_id","Required carried item type; chooses one compatible unenchanted main-inventory item, preserving its other components."),
                            field("offer_tier","integer","Native offer tier 1..3, default 1; never silently switches to another tier."),
                            field("max_levels_spent","integer","Required maximum actual player levels consumed, 0..3. This is not the offer's required level, which must also be met."),
                            field("max_lapis","integer","Required maximum lapis lazuli items consumed, 0..3. Insufficient material or budget stops before submission."),
                            field("search_radius","integer","Nearest-table search radius in loaded terrain, 1..64 blocks, default 32; exact targets retain their given position.")));
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
            case "maicraft:build", BuildDesignAdapter.ABILITY -> contract(
                    BuildDesignAdapter.ABILITY.equals(ability)
                            ? "Execute this ability to display an authored scene, explicit blueprint or frozen project as a read-only preview, even with Dev off. Supply scene, scene_id, blueprint or a saved project_id; natural-language outcomes do not generate geometry. No movement, acquisition or construction is permitted. Unloaded model cells fail without exploration. Local confirm cannot start construction; use maicraft:build separately. plan alone validates and stores the Goal."
                            : "Construction requires an LLM-authored scene, saved scene_id or explicit block blueprint. Natural-language outcomes alone cannot build. Model operations stay inside this build ability: create_scene, update_scene, get_scene_info, get_object_info, get_component_info, preview, export_scene, revise_project and build. Only build changes the Minecraft world. Keep named objects, transforms, material slots and Boolean modifiers like Blender; the Mod voxelizes and executes the final cells. This is a declarative modelling subset, not a Python/bpy interpreter. Reuse scene_id for edits/preview/build and project_id for interrupted construction; never replan current_place to resume.",
                    targets("area", "landmark", "coordinates", "current_place", "prior_result"),
                    fields(
                            // 编辑产生新模型后，可明确把同一坐标范围的修订交给旧项目；采用修订与真正施工分开。
                            field("operation", "string", "Model operation: create_scene, update_scene, get_scene_info, get_object_info, get_component_info, preview, export_scene, revise_project or build. Omitted operation defaults to build for maicraft:build and preview for maicraft:design_build; a scene, scene_id or blueprint is still required. revise_project adopts a direct child scene revision for an idle project, preserving its policies and verified scaffolds; it changes no world blocks. Use only project_id to resume a frozen blueprint without a model operation. These run through plan/execute; operations other than build never start body work."),
                            // 模型与平面网格先完整展开和校验，再进入既有预览/供料/施工；半砖上下状态也由作者明确指定。
                            field("scene", "object", "LLM-authored scene; v1 cube/panel semantics remain supported. v2: {schema_version:2,coordinate_system:'minecraft_y_up',materials:{Wall:{block_id:'minecraft:oak_planks'}},components:{Column:{objects:[{name:'Shaft',type:'MESH',primitive:'cube',location:[0.5,1.5,0.5],dimensions:[1,3,1],material:'Wall'}]}},objects:[{name:'Columns',type:'INSTANCE',component:'Column',location:[0,0,0],array:{count:[4,1,1],step:[3,0,0],skip:[[1,0,0]]}}]}. MESH and INSTANCE allow array, quarter-turn rotation_euler on all axes and mirror:['x']; INSTANCE also supports material_map. MESH supports triangle, wedge, triangular_prism, tetrahedron/triangular_pyramid, pyramid, prism, cylinder, cone, convex_polyhedron, fill:solid/hollow, wall_thickness, open_faces, face_materials, edge_material/edge_materials and edge_width. A panel may use pattern:{axes:['x','y'],rows:['01','10'],materials:{'1':'Upper','0':'Lower'}}; materials is optional, 1 defaults to the panel's painted material and 0 to an air hole. Either digit may start a row. Rows tile from the local minimum corner along the two Minecraft Y-up local axes, including Blender scenes; the remaining dimension must be 1. Named pattern materials follow instance material_map and block-state transforms; use slab properties.type top/bottom for alternating halves. Patterned panels may be cut but cannot serve as solid Boolean cutters. v2 block_state_axes defaults to local; minecraft_world preserves world-facing states. overlap_policy:last_wins/error. Blender coordinates remain supported. Root objects and their referenced definitions generate geometry; unused component definitions do not build. Cycles, invalid nested fields, material/face names and expansion budgets reject before construction. See blueprint knowledge for complete conventions."),
                            field("scene_id", "string", "Immutable saved scene revision returned by model operations. Reuses its original world, dimension and anchor; omit target. Update returns a new revision; old scenes and active builds are retained."),
                            field("edits", "object", "update_scene only: {schema_version:2,objects:[{name:'Columns',array:{count:[6,1,1],step:[3,0,0],skip:[[2,0,0]]}}],components:{Column:{objects:[...]}},remove_components:['UnusedColumn'],materials:{Wall:{block_id:'minecraft:stone_bricks'}},remove_objects:['OldRoof']}. schema_version:2 explicitly migrates v1 and preserves old nodes' minecraft_world material axes. v2 fields do not silently upgrade old scenes. block_state_axes and overlap_policy can also be updated. Root objects merge by name; components and named materials replace their entire definition. Remove and edit the same name is rejected. The complete merged reference graph is validated before saving a new immutable revision; old scenes and active projects remain unchanged."),
                            field("object_name", "string", "get_object_info only: exact root node name or an expanded node path returned by inspection."),
                            // 先查询组件定义的结构和材质，再决定复用或编辑；查询本身不展开施工任务。
                            field("component_name", "string", "get_component_info only: exact named component definition; requires scene_id. Read-only inspection, without construction."),
                            field("page", "integer", "Zero-based query page. get_scene_info lists 10 source nodes/components per page; v2 get_object_info/get_component_info list 64 expanded paths per page. Component-local sample paths are not scene object names. Generated paths are read-only; edit source objects or their component definition."),
                            field("format", "string", "export_scene only: json (default) or vanilla structure nbt; exports a reusable file and reports the NBT minimum-offset translation."),
                            field("blueprint", "object", "Alternative to scene or scene_id: {schema_version:1,blocks:[{offset:[x,y,z],block_id:'minecraft:stone',properties?:{...}}]}. Exact Minecraft block coordinates relative to target; omitted cells stay untouched. Omitted state properties are unconstrained; declared properties are final requirements, not preflight replacement conditions. Omit door open unless its final state matters. Supported native state changes run after construction; unsupported final changes fail without secretly breaking blocks. These state rules also apply to scene materials. Supports direct per-block design without semantic templates."),
                            field("project_id", "string", "Resume the frozen construction project returned in task progress/results. Ordinary resume cannot combine with new design/site/material parameters. Alternatively operation=revise_project accepts only this project_id and a direct child scene_id, with unchanged target coordinates and no active body task; then resume separately to apply real changes."),
                            // 材质由蓝图确定；这里只决定取料来源，不能因为缺料改变作者设计的方块种类。
                            field("material_policy", "string", "Specified (default, ordinary supply), inventory_only, storage_available or ordinary. All preserve the exact authored materials; this selects supply sources, never replacement palettes."),
                            field("replace_existing", "boolean", "Explicit permission to replace occupied cells; default false."),
                            field("protected_labels", "array<string>", "Remembered areas whose previously measured footprint supply and construction must preserve.")));
            case "maicraft:light_area" -> contract(
                    "Discover a semantic area's connected block boundary, construct lighting and verify actual block-light coverage.",
                    targets("area", "landmark", "coordinates", "current_place", "prior_result"),
                    fields(
                            field("radius", "integer", "Optional explicit player-authored geometric boundary. Do not invent one; when omitted MaiCraft progressively closes the connected component from the semantic landmark seed."),
                            field("minimum_light", "integer", "Required observed block-light threshold, 1-15."),
                            field("coverage", "string", "All, most, crop_growth or player_visibility; defines which observed cells count. Use crop_growth for a cultivated farm so farmland/crops, not open terrain, define its component."),
                            field("block_id", "resource_id", "Optional preferred light-source item/block, never a placement instruction."),
                            field("light_preferences", "array<resource_id>", "Ordered aesthetic source preferences; carried alternatives remain usable."),
                            field("material_policy", "string", "Ordinary, storage_available or inventory_only; storage is inspected before automatic crafting."),
                            field("allowed_sources", "array<string>", "Permitted semantic supply sources; never slots, routes or cells."),
                            field("allow_harm", "boolean", "Explicit harmful acquisition permission; false unless the player grants it."),
                            field("protected_labels", "array<string>", "Remembered areas/possessions that placement must not touch."),
                            field("style", "string", "Auto, ground or unobtrusive; MaiCraft still chooses cells. Wall and hanging layouts are not yet supported."),
                            field("max_placements", "integer", "Optional explicit total placement budget; omit it to let measured coverage and convergence end the task."),
                            field("placement_preference", "string", "Safe-candidate tie-break after measured coverage gain: coverage_optimal (default), central_unplanted (crop_growth only) or unobtrusive; MaiCraft still chooses cells.")));
            case "maicraft:connect_mechanical_power" -> contract(
                    "Connect two semantic mechanical networks while respecting axes, stress and protected terrain.",
                    targets("landmark", "area", "prior_result"),
                    fields(
                            field("source_label", "string", "Existing powered network or landmark."),
                            field("target_label", "string", "Destination machine, structure or landmark."),
                            field("transmission", "string", "Requested family such as chain_drive."),
                            field("allow_new_receiver", "boolean", "May terminate at the nearest authoritative endpoint evidence when that evidence is a verified empty receiver rather than a machine."),
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
                    "Run semantic child goals in order; each child remains independently observable and recoverable, while explicit area protection can span later children.",
                    targets(),
                    fields(field("protected_labels", "array<string>",
                            "Remembered areas whose internally measured footprint every later child must preserve; never provide cells or coordinates.")));
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
                "Use only fields declared by this ability. Build accepts LLM-authored scenes and explicit block blueprints; machine blueprints also accept exported tutorial structures. MaiCraft owns native routes, gestures, transactions, retries and verification. Never supply click scripts. Construction verifies structure; operation requires separate evidence.");
        return result;
    }

    private static JsonObject productionField() {
        // 默认只指向统一版本契约；每个原生机制的完整参数随知识读取或实际场地观察按需提供。
        return field("production", "object", "Versioned intent: v1 machine network; v2 finite native process. Read maicraft://knowledge/processes for full formats, then inspect_machine for applicable native contracts. Offsets share the build/survey anchor. run_production executes; watch_production supports v1 only.");
    }

    public static boolean compatibilityAlias(String ability) { return EnchantAbilityAdapter.ABILITY.equals(ability); }

    private static String utilityInputs(boolean concrete) {
        return "Fixed machines prefer shared city utility supply. external_inputs:[{id,medium,"
                + (concrete ? "offset:[x,y,z],face,block_id" : "consumers:[component_name],face?")
                + ",minimum_rpm?,resource?,reason?}]. Media: kinetic, energy, fluids, chemicals, items. "
                + "Default one input per medium; at most three per medium with a reason on each, eight total. "
                + "These are passive physical connection points, not generators. supply_preference defaults to external; "
                + "onsite requires onsite_reason explaining a deliberate local source. In survival, known creative-only "
                + "materials require real carried items or installed recipe evidence; that evidence is not a complete acquisition plan. "
                + "Build external-input machines without production, then inspect and connect_external_input; kinetic inputs may compare nearby loaded sources when source_label is omitted. "
                + "run_production is separate. Read remembered ports from perceive(machines).";
    }

    private static JsonObject blueprintField() {
        return field("blueprint", "object", "Alternative to design/blueprint_uri (modify: apply_blueprint only). Shape: {schema_version:1,blocks:[{offset:[x,y,z],block_id:'namespace:id',properties?:{property:'value'}}],metadata?:{...},evidence?:{...}}. Integer offsets are relative to the exact surveyed anchor; omitted cells are preserved and minecraft:air declares removal. Evidence may contain observed NBT or animation transforms; it does not configure the built machine. Unsupported desired configuration is rejected, never silently applied. Copy and edit Ponder chapter JSON or author your own layout. " + utilityInputs(true));
    }

    private static JsonObject blueprintUriField() {
        return field("blueprint_uri", "string", "Alternative to design/blueprint: exact maicraft://knowledge/ponder/structure/... URI returned by Ponder resources. Read the chapter first to capture it; the Mod resolves the cached structure without echoing all coordinates through model context. In modify_machine this is apply_blueprint only.");
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
