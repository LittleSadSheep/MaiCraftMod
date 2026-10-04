// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.LinkedHashSet;
import java.util.Set;
import org.maiwithu.maicraft.core.build.BuildingBudgetReport;
import org.maiwithu.maicraft.core.integration.machine.MachinePlanningBudget;
import org.maiwithu.maicraft.mcp.knowledge.KnowledgeReferences;

/** 向模型说明公开语义字段的契约，不暴露内部动作细节。 */
public final class SemanticAbilityCatalog {

    private SemanticAbilityCatalog() {}

    public static JsonObject describe(String ability) {
        JsonObject description = describeContract(ability);
        // 模型已经选定能力后，相关资料随完整契约出现；入口只供具体知识缺口使用，不增加执行前置步骤。
        JsonArray references = KnowledgeReferences.forAbility(ability);
        if (!references.isEmpty()) description.add("related_knowledge", references);
        if (AcquireAbilityAdapter.ABILITY.equals(ability) || GeneralAbilityAdapter.HARVEST_BLOCK.equals(ability)
                || "maicraft:find_block".equals(ability)) {
            // 挖矿选材契约直接给出随行补光用法，模型无须停矿另开一串逐格插灯任务。
            JsonArray tips = new JsonArray();
            tips.add("随行补光默认关闭。挖矿或找矿需要照明时，先备好火把，再用 maicraft:auto_light(action=enable,minimum_light=8) 显式开启；可随时用 action=disable 关闭，不替换当前任务。开启后整叠火把放副手，低于目标方块光时边走边放，不绕路。忙于挖掘、战斗、菜单或没有支撑时让位，缺火把只报告，不自动离开采集。");
            tips.add("用 auto_light(action=status) 查看已走路线的实际最低光、暗格和未知项；自动模式不保证远离路线的洞穴全覆盖。基地或指定区域使用 light_area，默认 coverage=all、minimum_light=8，并按实测补漏。光照验收不等于消灭已有怪物或阻止所有特殊刷怪。");
            description.add("tips", tips);
        }
        if (MachineAbilityAdapter.DESIGN.equals(ability) || MachineAbilityAdapter.BUILD.equals(ability)
                || MachineAbilityAdapter.MODIFY.equals(ability)) {
            var budget = MachinePlanningBudget.current();
            JsonObject limits = new JsonObject();
            limits.addProperty("max_targets", budget.maxTargets()); limits.addProperty("max_components", budget.maxComponents());
            limits.addProperty("max_connections", budget.maxConnections()); limits.addProperty("max_radius", budget.maxRadius());
            limits.addProperty("search_visited_budget", budget.searchVisitedBudget());
            limits.addProperty("configuration", "Owner-adjustable JVM properties: maicraft.machine.planning.*");
            description.add("planning_budget", limits);
        }
        // 保存、预览和实际建造都公布当前建筑配置，不把机器规划默认值误当成整座建筑上限。
        if ("maicraft:build".equals(ability) || BuildDesignAdapter.ABILITY.equals(ability))
            description.add("planning_budget", BuildingBudgetReport.current());
        return description;
    }

    private static JsonObject describeContract(String ability) {
        return switch (ability) {
            case PhysicsAbilityAdapter.ABILITY -> PhysicsAbilityAdapter.contract();
            case PhysicalAssemblyAbilityAdapter.ABILITY -> PhysicalAssemblyAbilityAdapter.contract();
            case PhysicalControlAbilityAdapter.ABILITY -> PhysicalControlAbilityAdapter.contract();
            case AircraftFlightAbilityAdapter.ABILITY -> AircraftFlightAbilityAdapter.contract();
            case QuestAbilityAdapter.ABILITY -> QuestAbilityAdapter.contract();
            // 用途交给模型判断；执行器只确认死亡不掉落、执行原生危险动作并如实回报死亡证据。
            case SuicideAbilityAdapter.ABILITY -> contract(
                    "Deliberately seek native death with keepInventory enabled, for prolonged lack of food or an inconvenient return to the respawn point. Never automatic from hunger or distance. Temporarily suppresses survival reflexes (food, retreat, breathing and fall rescue), walks into nearby lava, approaches hostile mobs without fighting, or steps off an observed ledge. No kill command, health edits, terrain edits or inventory edits. Restores reflex scheduling on pause, cancellation, failure, timeout or death. Success confirms death only; native respawn is requested by default and agent.respawned separately confirms it. Set auto_respawn=false to leave respawn to an explicit decision. Remaining sequence steps stay paused for reassessment after respawn. Missing/unreachable hazards, immunity or surviving a fall are honest failures, not invented deaths.",
                    targets(), fields(
                            field("keep_inventory_confirmed", "boolean", "Confirm keepInventory=true when the remote server rule is already known. Required true on multiplayer because vanilla clients do not reliably receive this gamerule. Singleplayer reads the actual server rule; a known false value always stops the task. Never changes gamerules."),
                            field("method", "string", "auto (default), lava, hostile or fall. Auto selects among actually observed nearby hazards; no player targets."),
                            field("search_radius", "integer", "4-64 blocks from the initial position, default 24; loaded terrain within twelve blocks vertically. No exploration or destructive preparation."),
                            field("timeout_seconds", "integer", "10-600 active seconds, default 120; each hazard without further health loss is abandoned after 20 active seconds.")));
            case ChatAbilityAdapter.ABILITY -> contract(
                    "Open the real game chat box, visibly type one complete message or slash-prefixed command, then submit it once through native chat handling. Uses the current player's permissions and loader command hooks. No foreground window or keyboard simulation is required. Existing human chat, containers and manual pause menus are preserved; an invisible background focus-loss pause may be replaced. Human input/Esc cancels automation, and task pause retains the draft. Success means submitted_to_client, not confirmed server delivery or command execution. Follow Attention for task status and maicraft://chatflow for received replies. Reuse one execute request_key for transport retries. Restarted operations with a prior reservation or untracked legacy history are not resent automatically; inspect history before starting a new send.",
                    targets(), fields(
                            field("text", "string", "Required single line, 1-256 UTF-16 characters. A leading / submits a command; otherwise sends public player chat. Uses vanilla whitespace normalization. No control characters or section-sign formatting; Chinese and complete Unicode graphemes are supported."),
                            field("typing_interval_ms", "integer", "Time between displayed characters, 50-1000 ms, default 100. A slow client may take longer; it never bursts to catch up. The completed draft remains visible for 250 ms before automatic submission.")));
            case MachineAbilityAdapter.INSPECT -> contract(
                    // 新工地走一次场地感知；这里保留查看已有设备、菜单操作前取证及移动结构观察。
                    "Inspect an existing machine or observed physical structure. Default mode=full exports actual map blocks/states as as_built_blueprint, never copied from the saved design. mode=diff directly compares current targets to the entire recorded design; it returns blueprint_diff without a new region survey, component inventory scan or snapshot_id. Reuse the existing construction anchor for subsequent blueprint edits. Newly completed machines are automatically recorded and receive one completion diff; there is no continuous build-time diff. For new construction use perceive(view=construction_site). Full world surveys retain native component evidence and snapshot_id; structure_id retains its separate moving-structure observation.",
                    targets("current_place", "coordinates", "landmark", "area", "prior_result"),
                    fields(
                            field("label", "string", "Short durable machine label; required unless the target already supplies one. Stored as a landmark; observing grants no mutation authority."),
                            field("machine_id", "string", "Recorded machine ID from a build result or perceive(view=machines). Resolves its saved location; target may be omitted."),
                            field("mode", "string", "full (default): actual map blocks and states in the recorded machine extent; diff: differences against the saved design. Omit with structure_id."),
                            field("offset", "integer", "Continue from next_offset: a capture-cell offset in full mode or compiled-target offset in diff mode. Default 0; pages are fresh observations, not one atomic snapshot."),
                            field("limit", "integer", "Cells/targets read per page, 1-512, default 256. Follow next_offset while has_more is true."),
                            field("radius", "integer", "World survey cube radius 0-8, default 4. In full mode an explicit radius overrides the recorded capture extent; without a machine record it supplies the extent. Unloaded evidence remains unknown."),
                            field("component_offset", "integer", "Continue optional server component observations from the previous server_evidence.next_component_index, 0-768. Each inspection retains its own snapshot identity/tick; pages are not one atomic world snapshot. Omit with structure_id."),
                            field("resource_offset", "integer", "Continue the starting component's resources from server_evidence.next_resource_offset, 0-4096, together with its next_component_index. Later components start at zero. Omit with structure_id."),
                            field("structure_id", "string", "Observed physical-structure UUID. Inspects its native blocks and control circuits; omit target and radius. Does not assume the structure can be driven.")));
            case MachineAbilityAdapter.DESIGN -> contract(
                    "Optional machine review without construction. The normal build workflow is perceive construction_site, author blueprint, plan build_machine, then execute plan_id. Supply exactly one of blueprint, blueprint_uri or legacy design. Read machine_assembly or component references for concrete missing design details. Materials and live power are checked during execution. Preserve expected_output and forbidden_mods; review does not claim completed construction or production.",
                    targets(MachineDesignBindings.TARGET_KINDS.toArray(String[]::new)),
                    fields(field("design", "object",
                            "Legacy logical-layout compatibility: components [{name,block_id,count,role}], connections [{from,to,medium,purpose}], optional expected_output and constraints.forbidden_mods. It does not give the author full control over work surfaces or transport choice. Use explicit blueprint assembly for new composite machines rather than selecting a product/workstation template. " + utilityInputs(false)),
                            blueprintField(), blueprintUriField(), productionField(),
                            field("snapshot_id", "string", "For a generic review omit both target and snapshot_id. A site review uses a current observation's snapshot_id and its exact landmark/area target. Normal construction goes directly through plan build_machine with construction_site's values; a separate review is optional. Blueprint offsets are relative to the observed anchor.")));
            case MachineAbilityAdapter.BUILD -> contract(
                    "Construct the authored blueprint at construction_site's target and snapshot_id. Normal workflow: perceive construction_site, author blueprint, plan then execute plan_id. Read references only for concrete design gaps. plan validates native installation; no separate inspect_machine, design_machine or material investigation is required. MaiCraft supplies materials, checks the full footprint and verifies native placement. With production and allow_use=true, run the declared v1 network or v2 native process afterward. Dev mode waits for local preview confirmation.",
                    targets("landmark", "area"),
                    fields(
                            field("snapshot_id", "string", "Copy construction_site's snapshot_id. Reuse the same-session anchor through construction and retries; the executor reads current target blocks. Refresh after a session change. A complete inspect_machine receipt is also accepted. Observation age does not invalidate a receipt."),
                            field("design", "object", "Alternative to blueprint/blueprint_uri: same semantic graph as design_machine, including external_inputs and supply_preference. " + utilityInputs(false)),
                            blueprintField(), blueprintUriField(), productionField(),
                            field("allow_modify", "boolean", "Set true when the player's instructions authorize construction at this site."),
                            field("allow_use", "boolean", "Required true with production: authorizes operating the declared machine after construction. Omit when production is absent."),
                            field("material_policy", "string", "ordinary, storage_available (including an existing AE2 network), or inventory_only; exact machine items are never substituted."),
                            // 建造授权直接覆盖声明范围；额外字段用于主动保留，不能诱导模型再次为同一块地申请许可。
                            field("replace_existing", "boolean", "Defaults true within authored blueprint targets; set false only to deliberately preserve occupied cells. Unlisted cells are outside this replacement scope."),
                            field("replace_block_entities", "boolean", "Defaults true when replacement is enabled, including declared old machine components; set false only to deliberately preserve block entities."),
                            field("protected_labels", "array<string>", "Remembered areas that construction and material acquisition must preserve.")));
            case MachineAbilityAdapter.OPERATE -> contract(
                    "Use surveyed machines through native evidence. run_production accepts a v1 production network or a v2 native process; inspect_machine supplies matching mechanism contracts. Existing menu transfers, controls and AE2 supply retain their own evidence requirements. Success reports verified effects.",
                    targets("landmark", "area", "nearest", "coordinates", "prior_result"),
                    fields(
                            field("operation", "string", "run_production, watch_production, cancel_watch, drive_vehicle, open_menu, close_menu, deposit, withdraw, set_control or ae2_supply. run_production executes v1 networks or finite v2 processes. watch_production registers a v1 server read-only monitor before a future batch; registration is not completion, and it releases the body without refills or forced chunk loads. cancel_watch stops monitoring only. close_menu closes an owned machine menu or ordinary player inventory with empty cursor and crafting grid. close_menu/cancel_watch omit target."),
                            productionField(),
                            field("job_id", "string", "cancel_watch only: exact job_id from monitor registration or perceive(machines). Monitors belong to the current player connection and dimension; reconnect requires new registration."),
                            field("minimum_process_events", "integer", "watch_production only: required native completions per process, 1-100, default 1. Use a small repeated sample for commissioning; exact requested target output still comes from production.observation.minimum_output."),
                            field("idle_ticks", "integer", "watch_production only: loaded time with no progress before Attention requests inspection; 20..max_duration_ticks, default the smaller of 6000 and max_duration_ticks. Unloaded machines remain unknown and do not accrue inactivity."),
                            field("max_duration_ticks", "integer", "watch_production only: finite monitor duration 20-72000 ticks, default 72000. It does not keep the chunk loaded or survive a connection/world change."),
                            field("protected_labels", "array<string>", "run_production only: remembered areas that navigation and material acquisition must preserve."),
                            field("material_policy", "string", "run_production: v1 configuration-tool acquisition policy; v2 accepts inventory_only and uses carried inputs. Acquire missing inputs separately. build_machine uses its existing construction supply policy."),
                            field("structure_id", "string", "drive_vehicle only: observed physical-structure UUID. target is the destination. Existing control bindings are preserved; no force/stability model is assumed."),
                            field("allow_use", "boolean", "Required true only when the player's instructions authorize this use of the machine/network; never infer ownership from a label."),
                            // 生产复用同会话机器范围并重验现场；有时效的菜单、库存交易与控制观察仍分别绑定原回执。
                            field("snapshot_id", "string", "Receipt for the exact target label, consumed by operation. All receipt-bound operations revalidate observed geometry, regardless of observation age. Changed geometry returns latest_snapshot with its matching target and snapshot_id; review it before retrying without another observation call."),
                            field("component_index", "integer", "open_menu only: index of the desired block in snapshot.relative_blocks; omit to use the marked center. Must come from that exact observation."),
                            field("menu_receipt_id", "string", "deposit/withdraw only: receipt from perceive(view=machine_menu) after open_menu. Omit target; the receipt already binds the exact native menu. Inspect again after each transaction."),
                            field("entry_index", "integer", "deposit/withdraw only: exact observed native menu entry. MaiCraft chooses player inventory entries, validates slot rules and never quick-moves or swaps arbitrary contents."),
                            field("powered", "boolean", "set_control only: desired lever state, required. This is control state, not measured production."),
                            field("control_label", "string", "set_control only: remembered exact vanilla lever within the surveyed region; omit only when exactly one lever is present."),
                            field("item_id", "resource_id", "ae2_supply/deposit/withdraw: exact requested registered item."),
                            field("count", "integer", "Exact item quantity, default 1: transfer 1-64, AE2 approved net increase 1-256. Use a stable execute request_key to prevent uncertain transport retries creating duplicate work."),
                            field("allow_crafting", "boolean", "ae2_supply only: may submit an existing AE2 crafting pattern, default false. Requires target={kind:nearest} without a label; selecting a specific surveyed network is unsupported.")));
            case MachineAbilityAdapter.MODIFY -> contract(
                    "Modify an existing machine. MaiCraft refreshes its own world observations internally; a separate inspection request is optional. apply_blueprint applies explicitly declared changes, including removing old components, and preserves omitted positions; exactly one blueprint or blueprint_uri is required. Success verifies the requested structural change; running and production are checked separately.",
                    targets("landmark", "area"),
                    fields(
                            field("operation", "string", "apply_blueprint, connect_mechanical_power or connect_external_input. External utility hookup is a separate task after construction; MaiCraft refreshes the named target internally."),
                            field("snapshot_id", "string", "Optional prior observation reference. Modification resolves the named target and refreshes its own observations; no repeated inspect is required."),
                            field("source_label", "string", "Remembered existing outlet in the same dimension. For a kinetic connect_external_input, omit to compare nearby loaded powered sources; an explicit source remains fixed. Required for other utility media and connect_mechanical_power."),
                            field("source_radius", "integer", "Kinetic automatic horizontal search radius, 8..128, default 64. Sources must be visible and within 4 blocks of current work height; hidden/protected outlets are excluded. Use perceive(view=kinetic_sources, query?, radius?) for compact candidate source_labels. Never infer ownership from proximity; explicitly named authorized sources retain their own checks."),
                            field("input_id", "string", "connect_external_input only: exact external input id from perceive(machines). Create compares native interfaces and full transmission costs; FE/Mek cable hookup requires server assistance. Other media reject before mutation."),
                            blueprintField(), blueprintUriField(),
                            field("material_policy", "string", "apply_blueprint/connect_external_input: ordinary, storage_available or inventory_only."),
                            field("replace_existing", "boolean", "apply_blueprint defaults true for the already authorized declared changes. Set false only to deliberately preserve occupied cells. Declare minecraft:air to remove a named cell."),
                            field("replace_block_entities", "boolean", "apply_blueprint defaults true when replacement is enabled; declared old machine components need no separate permission flag. Set false only to deliberately preserve block entities."),
                            field("protected_labels", "array<string>", "apply_blueprint/connect_external_input: remembered areas that modification and material acquisition must preserve."),
                            field("allow_modify", "boolean", "Required true when the player's instructions authorize this change; existing authorization carries through the workflow.")));
            case GeneralAbilityAdapter.FIND_ENTITY -> contract(
                    "Find real entities through loaded client evidence and bounded first-person frontier exploration; MaiCraft owns every route and concrete identity.",
                    targets("entity", "nearest", "area", "landmark", "current_place"),
                    sheepFields(fields(
                            field("entity_type_id", "resource_id", "One acceptable registered entity type; never a runtime entity ID."),
                            field("entity_type_ids", "array<resource_id>", "Acceptable registered entity types; never runtime entity IDs."),
                            field("relation", "string", "Wild, hostile, unowned or any. Wild/unowned preserve named, tame, owned, leashed or vehicle-held entities; enclosure counts only inside an explicitly protected semantic area."),
                            field("count", "integer", "Required distinct observed count; a partial count is not success."),
                            field("max_distance", "integer", "Bounded physical search distance from start; default 512, maximum 2048."),
                            field("may_alter_terrain", "boolean", "Hard consent for route digging, bridging or pillaring; default false."),
                            field("protected_labels", "array<string>", "Remembered areas or possessions that matching evidence must not use."))));
            case GeneralAbilityAdapter.FIND_BLOCK -> contract(
                    // 岩浆查找随默认回执交付整池事实，模型不必靠连续查询单格岩浆猜测池子的规模。
                    "Find named blocks through visible loaded client evidence; nearest_match_position reports the nearest observed match. "
                            + "Reports counts, matching ids and distances. For minecraft:lava, even count=1 completes the bounded scan and returns lava_pool_survey: "
                            + "connected visible surface-source counts, straight-bank lengths and casting candidates with platform fill costs and remaining-source lower bounds. "
                            + "Compare candidate.reserve_observed (at least 15 sources after filling) before choosing lava_cast; a block match alone does not establish a usable pool. "
                            + "Set purpose=portal_casting to make success require count matching pools with that geometry and reserve; block selectors may be omitted and default to lava. "
                            + "For portal_casting, nearest_match_position belongs to a matching pool, and is absent when no pool matches; a lava source position is not a safe standing position. "
                            + "Hidden connections, depth, bucket access and native fluid outcomes remain unverified. If evidence is insufficient, explore or change viewpoint instead of repeating the same scan.",
                    targets("current_place", "nearest"),
                    fields(
                            field("block_id", "resource_id", "One acceptable registered block type."),
                            field("block_ids", "array<resource_id>", "Acceptable registered block types."),
                            field("purpose", "blocks|portal_casting", "Default blocks. portal_casting searches only lava and counts suitable connected pools, with a casting start row and at least 15 sources after planned filling; native access and fluid outcomes remain unverified."),
                            field("count", "integer", "Required observed block positions, or matching pools with purpose=portal_casting; default 1. Partial counts fail."),
                            field("max_distance", "integer", "Bounded loaded-world scan radius from the standing place; default 64, maximum 128.")));
            case GeneralAbilityAdapter.COMBAT -> contract(
                    "Defend against or engage semantic living targets visible in loaded terrain; MaiCraft resolves concrete entities and combat movement.",
                    targets("entity", "player", "nearest"),
                    sheepFields(fields(
                            field("mode", "string", "Defend, engage or defeat; defend may select an immediate hostile threat."),
                            field("entity_type_id", "resource_id", "Optional namespaced entity type, never a runtime entity identifier."),
                            field("entity_name", "string", "Optional visible custom/display name."),
                            field("player_name", "string", "Optional exact player name."),
                            field("selection", "string", "Use nearest only when any matching loaded target is acceptable."),
                            field("count", "integer", "Maximum number of matching semantic targets."),
                            field("radius", "integer", "Bounded loaded-world search radius."),
                            field("allow_harm", "boolean", "Required explicit consent because combat can harm or kill."),
                            field("confirm_risky_target", "boolean", "Second confirmation for players, tame/named or non-hostile targets."))));
            case GeneralAbilityAdapter.INTERACT -> contract(
                    "Use one semantic block or entity; MaiCraft resolves the loaded target, approaches it and performs the ordinary interaction. "
                            // 满桶坐标描述期望落格，执行器自行选择支撑面；不让模型把点击面和倒桶目标混为一谈。
                            + "For a filled bucket, a coordinate target is the destination cell and may be air; MaiCraft aims at a suitable support face. "
                            + "With a block_id and selection=nearest it self-locates the closest matching station within the radius — no coordinates needed. "
                            + "Stand level with the target block: submitting across a height difference has produced no real click with stale confirmations (UNCERTAIN).",
                    targets("coordinates", "entity", "player", "nearest", "landmark", "area"),
                    sheepFields(fields(
                            field("block_id", "resource_id", "Optional namespaced block type to use."),
                            field("entity_type_id", "resource_id", "Optional namespaced entity type, never a runtime entity identifier."),
                            field("entity_name", "string", "Optional visible custom/display name."),
                            field("player_name", "string", "Optional exact player name."),
                            field("item_id", "resource_id", "Optional carried item whose ordinary use is intended. Omit to prepare an empty main hand before interacting with the target."),
                            field("item_resource_id", "string", "Block use only, requires item_id: copy an observed resource_id from situation inventory variants or equipment.main_hand to choose that exact carried component variant. Without it, a matching current held stack is preferred."),
                            field("purpose", "string", "Open, talk, trade, use or till. Till prepares a suitable hoe when item_id is omitted."),
                            field("duration_seconds", "number", "Optional finite duration from 0 to 30 for empty-hand Create hand-crank use. Zero or omitted means one activation; the Mod repeats native uses and settles the final receipt."),
                            field("selection", "string", "Nearest means any nearest loaded semantic match is acceptable."),
                            field("radius", "integer", "Bounded loaded-world search radius."),
                            field("may_alter_terrain", "boolean", "Explicit route permission; default false."))));
            case GeneralAbilityAdapter.USE_ITEM -> contract(
                    // 明确坐标时由执行器走近并定点用物品；不带目标的加工批次继续准备双手、补料和换工具。
                    "Use a carried item. With target.kind=coordinates, approach and use it once at that exact cell: filled buckets place into the cell (air is allowed), empty buckets collect its source, and an ender eye is inserted into the targeted frame. MaiCraft chooses the native aim and item/block action. Without coordinates, use along the current view. Repeated processing at current_place accepts count plus expected_output_item_id and optional ingredient_item_id; the Mod prepares both hands and repeats only after confirmed output.",
                    targets("current_place", "coordinates"), fields(
                            field("item_id", "resource_id", "Required carried item to select and use through its own native behavior."),
                            field("block_id", "resource_id", "Optional current block identity at a coordinate target; omit when pouring into an empty cell."),
                            field("may_alter_terrain", "boolean", "Permit route preparation for coordinate targets; default false. Does not change the requested use location."),
                            field("ingredient_item_id", "resource_id", "Optional carried ingredient to equip in offhand; the tool is prepared in main hand. Native item behavior must support this pairing."),
                            field("count", "integer", "1..64 new output items to produce at current_place, default 1. Coordinate targets require one action and no ingredient_item_id."),
                            field("expected_output_item_id", "resource_id", "Required for batch/material preparation, optional for single use. Each completed native use must increase this carried output; reports completed and remaining counts.")));
            case GeneralAbilityAdapter.HARVEST_BLOCK -> contract(
                    "Harvest one exact observed resource block through native breaking and pickup. Stops after one source break even if it regenerates; reports source position and carried output increase. Approaches without altering surrounding terrain. The cell directly underfoot cannot be targeted — a zero-distance stance generates no approach route. No inventory-source substitution.",
                    targets("coordinates"), fields(
                            field("block_id", "resource_id", "Required observed source block; the loaded state must still match. Block entities and fluids are excluded."),
                            field("expected_output_item_id", "resource_id", "Required expected drop; success requires this carried output to increase after a confirmed source break."),
                            field("may_alter_terrain", "boolean", "Required true for breaking this one cell, including its native neighbor updates. Does not permit breaking surrounding structures.")));
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
                    // 已授权的自主游戏任务中，规划器可权衡饥饿与食物效果；该参数表达游戏策略选择，不新增人工审批步骤。
                    "Eat a suitable carried food through native timed use. The planner may choose food effects as an ordinary survival decision within the authorized game task; respect any explicit user restrictions. Inspect the food and current hunger before choosing.",
                    targets("current_place"),
                    fields(
                            field("item_id", "resource_id", "Optional exact carried food; omit to choose safe effect-free food."),
                            field("allow_effects", "boolean", "The planner explicitly accepts the named food's native status effects; default false. It may set true within an authorized autonomous game task without requesting another human confirmation.")));
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
            // 先用附近实体中的具体物品和位置选择一堆，再将引用交给执行器追踪，不要求模型猜路线。
            case GeneralAbilityAdapter.COLLECT -> contract(
                    "Walk to loose drops and collect through native contact. Copy drop_ref from perceive surroundings nearby_entities "
                            + "to select one exact stack; its current position is tracked as it moves. Lost targets remain unconfirmed, "
                            + "never substituted. Without drop_ref, collect nearby item_ids or all items when omitted. "
                            + "Native pickup may also absorb incidental nearby items. With may_alter_terrain=true, pickup navigation can clear permitted natural obstacles to reach a contact stance, including a one-block mining hole. It respects the clearance whitelist and protected areas. Completion requires native pickup and inventory evidence, not merely approaching within travel tolerance. No separate travel or break request is needed.",
                    targets("current_place"),
                    fields(field("drop_ref", "string", "Optional exact stack reference from nearby_entities."),
                            field("item_ids", "array<resource_id>", "Optional non-empty registered item filter; applies together with drop_ref."),
                            field("radius", "integer", "Loaded search range from the actor, 1–48 blocks; default 16."),
                            // 开路授权随拾取目标进入执行器；模型选物品和许可，执行器负责脚位、头顶与最后的原生收取。
                            field("may_alter_terrain", "boolean", "Allow pickup navigation to dig, bridge or pillar where the clearance whitelist and protections permit. Default false. Use true when collecting mining drops requires opening body clearance.")));
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
                    "Deposit, withdraw or balance against one loaded container. A coordinates target binds that exact block. MaiCraft approaches and transfers natively; receipts identify the actual container and distinguish transfer totals from remaining stock.",
                    targets("nearest", "landmark", "area", "coordinates"),
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
                            field("radius", "integer", "Bounded loaded-container search radius; default 32."),
                            field("may_alter_terrain", "boolean", "Explicit native route preparation permission; default false.")));
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
                    // 同层接近和精确落地分别说明，避免模型把可设为零的高度容差误读成不可收紧的提示。
                    "Reach a destination using caller-selected arrival precision. All ability fields belong in goal.parameters. "
                            + "For located travel, horizontal_radius defaults to 3 and vertical_tolerance to 2 blocks; "
                            + "set vertical_tolerance=0 with a known Y to require the same feet-node layer while allowing horizontal approach. "
                            + "exact=true instead requires the specified x/y/z cell and actual ground support. Arrival does not establish interaction reach or line of sight. "
                            + "With may_alter_terrain it digs/bridges/pillars its way there; elevator_floor rides observed elevators without coordinates; "
                            + "semantic_target discovers platforms, open-sky surface, biomes or coasts rather than an invented precise point.",
                    targets("coordinates", "landmark", "player", "entity", "nearest", "area", "prior_result"),
                    fields(
                            field("destination", "object", "goal.parameters.destination contains only {x,z,y?,dimension?}; omit goal.target and other destination selectors. Put exact, horizontal_radius and vertical_tolerance beside destination, never inside it. Supplied y defines the height checked by vertical_tolerance; omit y only when height is unknown. Existing coordinates targets still require all three axes."),
                            field("structure_id", "string", "Observed physical-structure UUID to board using an equipped Create jetpack. Omit target/destination/discovery fields; transport_mode must be auto or jetpack. MaiCraft selects a native deck face, follows its changing pose and confirms actual support on that vessel."),
                            // 起飞前可直接选择已观察的吊舱座位，执行器确认原生乘坐后才结束登艇。
                            field("seat_position", "object", "Optional with structure_id: exact integer {x,y,z} seat offset relative to origin_storage. Board near this seat, then use an empty-hand native click and confirm riding. Does not power or drive the vehicle."),
                            field("elevator_id", "string", "Optional observed elevator UUID from surroundings.elevators. Omit to select the nearest loaded elevator; use with elevator_floor and no coordinate destination."),
                            field("elevator_floor", "string", "ask (default for unlocated elevator travel), top, bottom, next_up, next_down, or an exact synchronized floor id/name. ask approaches the elevator, synchronizes its floors, then returns an LLM decision. Explicit floors synchronize first when needed. Use transport_mode=auto/elevator; do not guess a height."),
                            field("exact", "boolean", "Default false. True requires the specified x/y/z feet cell and actual ground support, with supplied or resolved Y; both tolerance fields are ignored. This is block-cell precision, not exact floating-point coordinates. For same-height approach with horizontal freedom, leave false and set vertical_tolerance=0."),
                            field("horizontal_radius", "number", "Finite nonnegative X/Z arrival radius in blocks; default 3 for located travel. Set in goal.parameters beside destination. Ignored when exact=true. Known legacy caveat: with omitted Y, radius 0 can still fall back to 3 blocks after path failure; use exact=true with observed Y for a strict cell."),
                            field("vertical_tolerance", "number", "Configurable finite nonnegative height allowance in blocks; default 2, and 0 is valid. Example goal.parameters: {\"destination\":{\"x\":10,\"y\":59,\"z\":20},\"horizontal_radius\":3,\"vertical_tolerance\":0}. This requires the Y=59 feet-node layer, so Y=61 cannot satisfy it. Applied only to located travel with supplied/resolved Y and exact=false; omitted Y leaves height open. Target coordinates are floored to block cells. Receipts retain target_y_hint and y_hint_delta."),
                            field("transport_mode", "auto|ground|jetpack|elevator|aircraft", "Default auto selects available native travel. aircraft uses a registered aircraft_id for a located destination: board, fly, land and stop, dismount, then finish the original ground destination. Platform and surface discovery support ground or continuous jetpack exploration. Elevator accepts a floor or located destination. Undiscovered coast/biome goals support ground/auto exploration. This grants no terrain-alteration permission."),
                            field("aircraft_id", "string", "Observed physical aircraft UUID with a saved fly_vehicle profile. Requires transport_mode=aircraft; distinct from structure_id, which only requests boarding. Use destination or a coordinate/landmark/area target."),
                            field("cruise_altitude", "number", "Optional finite world Y for aircraft cruise; only valid with transport_mode=aircraft. Landing and the final ground leg retain the original destination height and tolerances."),
                            field("allow_water_bucket_fall", "boolean", "Allow temporary bucket water for falls to a located destination, without granting digging or scaffold placement. Default false."),
                            field("allow_landing_assists", "boolean", "Allow verified temporary landing aids from carried items, including water and boats, without granting excavation or scaffolding. Requires a located destination; default false."),
                            field("semantic_target", "string", "Discover coast, a biome id/#biome_tag, platform, or surface. Platform discovery keeps observing while moving; no coordinates are required. Use direction to choose where to search. Surface discovery climbs out to open sky (no solid or liquid cover above the standing column; tree foliage is not cover) and needs no direction."),
                            field("direction", "string", "Platform: down/up or horizontal, default forward, radius 8..128. Biome discovery: cardinal/diagonal or forward/backward/left/right. Relative directions freeze at departure; candidate places must lie in the requested sector."),
                            field("angle_degrees", "integer", "Biome discovery: full sector width 1..360, default 90 with direction. Travel routes may detour."),
                            field("min_distance", "integer", "Biome discovery: minimum target distance, default 16 with direction or 0 without."),
                            field("biome_id", "resource_id", "Optional exact biome to discover."),
                            field("biome_tag", "resource_id", "Optional biome tag to discover."),
                            field("max_distance", "integer", "Bounded exploration radius; omit for the Mod default."),
                            field("may_alter_terrain", "boolean", "Hard consent to dig, bridge or pillar; default false."),
                            field("protected_labels", "array<string>", "Remembered areas whose previously measured footprint this movement must preserve.")));
            // 跑图和找地方共用原生探索；目录中的模组群系、标签及结构证据由 LLM 按用途选择。
            case ExplorationIntent.ABILITY -> contract(
                    "Explore the map, discover a chosen biome/tag or structure, or survey with no target kind. Discover actual modded IDs with perceive(view=exploration,focus=biomes|biome_tags|structures,query=...). Direction restricts destination candidates to a sector, not a straight walking line; after repeated unreachable legs the search rotates to the next bearing, and exhausting all bearings fails with frontier_legs_circuit_broken instead of looping. coast means minecraft:beach. Quality is chosen by the model from observed facts; no hidden seed/locate is used.",
                    targets(), fields(
                            field("biome_id", "resource_id", "Exact registered biome; choose at most one target selector."),
                            field("biome_tag", "resource_id", "Registered biome tag, including mod tags."),
                            field("structure_id", "resource_id", "Structure evidence profile discovered through the exploration catalog."),
                            field("semantic_target", "string", "coast, biome id/#tag, or survey; no selector defaults to survey."),
                            field("direction", "string", "Cardinal/diagonal or forward/backward/left/right; fixed at departure. Omit to search all directions."),
                            field("angle_degrees", "integer", "Full sector width 1..360, default 90 with direction."),
                            field("min_distance", "integer", "Minimum target distance, default 16 with direction or 0 otherwise."),
                            field("max_distance", "integer", "Radius: biome/survey 64..2048 (default 768); structures 64..4096 (default 4096)."),
                            field("transport_mode", "auto|ground", "Native movement preference; default auto."),
                            field("may_alter_terrain", "boolean", "Explicit permission to dig, bridge or pillar; default false."),
                            field("reach_structure", "boolean", "Structure only: reach and recheck evidence, default true."),
                            field("allow_rare_consumables", "boolean", "Structure only: permit real ender-eye throws, default false.")));
            // 独立备门把浇筑手法交给模型选择，执行器负责取放桶、补料和完整门框观察，完成后不自动穿门。
            case "maicraft:prepare_portal" -> contract(
                    "Prepare and ignite a portal, then stop outside it. lava_cast uses one bucket, an observed Overworld lava pool, a temporary mold and native water/lava reactions. "
                            + "The observed lava pool is both the material source and the portal site; a carried lava bucket does not substitute for it, and the casting task builds no portal without a verified pool bank. "
                            + "It prepares water before selecting the pool, but only searches loaded terrain; it does not explore distant resources. Use existing evidence to choose a site with a pool and water bucket/source. "
                            + "When resources are absent, use explore or travel to a known resource, then retry from the new area. find_block only scans loaded visible terrain. "
                            + "Acceptance is not proof of resource readiness or started construction: read resource_preparation and construction_phase_started. Action completion, whole-frame differences and portal activation are reported separately.",
                    targets("current_place"), fields(
                            field("destination_dimension", "resource_id", "Portal destination; default minecraft:the_nether."),
                            field("portal_method", "obsidian|lava_cast", "Default obsidian. Choose lava_cast for the single-bucket lava-pool technique; no diamond pickaxe or carried obsidian required."),
                            field("max_search_radius", "integer", "Loaded-world search radius, 16..512; default 128. Explore first if no pool is observed."),
                            field("may_alter_terrain", "boolean", "Required for construction: permits the declared mold, bottom excavation and frame replacement."),
                            field("material_policy", "string", "ordinary, storage_available or inventory_only for tools and mold supplies; native fluid collection is part of lava_cast."),
                            field("allowed_sources", "array<string>", "Permitted acquisition sources for supplies."),
                            field("allow_combat", "boolean", "Permit hunting for supplies; default false."),
                            field("allow_rare_consumables", "boolean", "Permit End portal eye consumption; default false."),
                            field("protected_labels", "array<string>", "Remembered places to preserve.")));
            case "maicraft:travel_dimension" -> contract(
                    "Reach another dimension through a real portal. With prepare_portal, MaiCraft can prepare a Nether or End entry portal before walking through and verifying the new dimension.",
                    targets("current_place", "landmark", "area", "prior_result"),
                    fields(
                            field("destination_dimension", "resource_id", "Required destination dimension, such as minecraft:the_nether or minecraft:the_end."),
                            field("max_search_radius", "integer", "Bounded loaded-world portal evidence radius; default 128."),
                            field("prepare_portal", "boolean", "If no active portal is observed, obtain materials and prepare one; default false. Nether construction/repair also needs may_alter_terrain; End eyes need allow_rare_consumables."),
                            field("portal_method", "obsidian|lava_cast", "Preparation method; default obsidian. lava_cast needs an observed pool and a water bucket or local collectable water. It prepares water first but does not explore for missing resources; use explore/travel before retrying an unchanged absence report."),
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
                            field("direction", "string", "Optional horizontal cardinal/diagonal or relative heading, fixed at start."),
                            field("angle_degrees", "integer", "Full sector width 1..360, default 90 with direction."),
                            field("min_distance", "integer", "Minimum target distance, default 16 with direction or 0 without."),
                            field("transport_mode", "auto|ground", "Native route preference, default auto."),
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
                            field("portal_method", "obsidian|lava_cast", "Nether preparation method; default obsidian. lava_cast needs an observed pool and a water bucket or local collectable water. It prepares water first but does not explore for missing resources; use explore/travel before retrying an unchanged absence report."),
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
                    // 合成入口只在背包或工作台格子中制作；加工类配方即使能在EMI里读到，也不因此成为格子合成动作。
                    "Craft through inventory or crafting-table grids using carried materials and recursively grid-craftable intermediates. "
                            + "Does not withdraw stock, mine or execute other recipe types. Use acquire_items with permitted sources for material acquisition; "
                            + "other processing requires the appropriate native item, block or machine operation. Recipe choice, workstation approach, "
                            + "bounded crafting-table preparation and synchronized GUI slots belong to MaiCraft. "
                            + "Recipes fitting the 2x2 inventory crafting grid craft without a crafting table; larger recipes use an auto-placed temporary crafting table that is recovered afterwards.",
                    targets("nearest", "prior_result"),
                    fields(
                            field("item_id", "resource_id", "Requested output."),
                            // 材料偏好允许模型引导整条依赖链；现成库存仍优先，偏好不等于额外取材许可。
                            field("preferred_materials", "array<resource_id>", "Optional soft item-ID hints for recipe routes and intermediates. Routes covered by available stock remain first; other usable routes remain eligible if the preferred route cannot complete. Does not expand allowed sources."),
                            field("count", "integer", "Requested final inventory count, not number of clicks.")));
            case "maicraft:cook" -> contract(
                    "Cook an item through an ordinary furnace-family workstation from the current location. "
                            + "Use a sequence with travel first to cook elsewhere; nearest accepts no qualifiers. "
                            + "Recipe, input, fuel quantity, workstation, path and synchronized GUI transactions belong to MaiCraft.",
                    targets("nearest"),
                    fields(
                            field("item_id", "resource_id", "Requested cooked output."),
                            field("count", "integer", "Required final main-inventory count from 1 to 2304, not operations; default 1."),
                            field("recipe_preference", "string", "Auto, fastest, preserve_rare, smelting, blasting, smoking or campfire; campfire currently returns a structured unsupported decision."),
                            field("allowed_fuels", "array<resource_id>", "Optional fuel policy; omit for a conservative ordinary-fuel set."),
                            field("allowed_sources", "array<string>", "Where MaiCraft may obtain input, fuel and a workstation. The cook source permits finite prerequisite cooking; output ancestors prevent production cycles and nesting is capped at eight levels. Fuel and protection policies are inherited."),
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
            case StonecutAbilityAdapter.ABILITY -> contract(
                    "Cut a carried input into the requested output at an existing loaded vanilla stonecutter. "
                            + "Uses a visible native GUI: loads the exact input count, resolves the real recipe list for that input, selects the requested output once, then quick-moves and verifies every crafted piece before returning leftovers and closing. "
                            + "No station construction, material acquisition or output substitution. A durable reservation blocks automatic repeats once crafting has begun; inspect before requesting a new operation.",
                    targets("coordinates","landmark","nearest"), fields(
                            field("item_id","resource_id","Required carried input type, one item per craft, for example minecraft:stone."),
                            field("output_item_id","resource_id","Required requested product; the input must have a real stonecutter recipe producing it, for example minecraft:stone_bricks."),
                            field("count","integer","Crafts to perform, 1..64, default 1; each craft consumes one input and is verified separately.")));
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
                    "Make requested inventory facts true using allowed sources from the player's current location — "
                            + "Ordinary acquisition investigates visible containers within 32 blocks of the fixed request origin: remembered-present first, then unvisited, then remembered-absent. Every synchronized visit updates durable container identity and complete inventory memory. "
                            + "mine starts from visible or remembered sources and approaches them by digging stairs, tunnels or pillaring through ordinary terrain (may_alter_terrain, default true; tree-shaped source filtering still decides what counts as a target); available FTB Ultimine can harvest the complete native vein preview within the requested material and search scope, including connected buried ore. With allow_prospecting, an empty fair scan starts a descending passage to the generation band followed by a horizontal tunnel. Native mining_tunnel and small_tunnel follow the actual clicked face; remaining obstacles are cleared until the player's swept body and floor permit walking, never by a fixed click count. harvest replants mature crops, and storage, trade, craft, cook, hunt and nearby collections are further families. "
                            + "To acquire elsewhere, use a sequence with travel first; nearest accepts no label, position or relation.",
                    targets("nearest"),
                    fields(
                            field("item_id", "resource_id", "One requested item; item_ids may express alternatives."),
                            field("item_ids", "array<resource_id>", "Acceptable alternatives, not an ordered recipe."),
                            field("item_tag", "resource_id", "A semantic item tag such as minecraft:beds or minecraft:planks; the Mod resolves live members."),
                            field("item_tags", "array<resource_id>", "Several semantic item tags combined as acceptable alternatives."),
                            field("preferred_materials", "array<resource_id>", "Optional soft item-ID hints for recipe routes and intermediates, inherited by prerequisites. Available stock remains first; unavailable preferences fall back within the same allowed sources."),
                            field("count", "integer", "Required final aggregate main-inventory count, from 1 to 2304; default 1. Already-held items count toward it, not an incremental request: request above current stock to force real gathering. item_ids form an interchangeable set, so submit separate goals to obtain several distinct items."),
                            // 普通取材先核对随身库存，再按三类记忆翻可见箱子；只有玩家明确限制来源才收窄许可。
                            field("allowed_sources", "array<string>", "Optional hard restriction: omit for ordinary acquisition, including storage, wireless and harvest. Carried inventory is checked first, then visible containers before other external sources. Only narrow this list for an explicit user restriction. Permitted families: inventory, nearby, wireless, storage, harvest, craft, cook, mine, trade, hunt. mine takes only sources visible now or previously observed; with allow_prospecting it continues after an empty fair scan by descending to the item's natural generation band and mining what its prospect tunnels expose. find_block and other exploration require direct line of sight. Mine source queries converge on exposed sources roughly 25-30 blocks below the standing position, so submitting from the surface for deep-oreband items (diamond, deep iron) honestly reports mined_out; descend to the generation band first (travel with a y hint, exact=true, may_alter_terrain=true) or authorize allow_prospecting in the same submission. A find_block line-of-sight hit is not automatically a fair-gate source; harvest one block to expose the vein and the gate converges on the exposed face. Gravity blocks (gravel, sand, pointed dripstone) collapse when their support is mined: breaking a low block drops the whole column above it, so work a pile from its top block downward or expect the remaining column to fall toward the agent. harvest replants loaded mature crops; nearby collects loose drops; wireless uses observed stock without ordinary containers or network crafting. Explicit restrictions and prerequisite inheritance remain enforced; list order is not execution order."),
                            field("allow_harm", "boolean", "Whether acquiring may harm living entities; default false."),
                            // mine 家族的接近性动土默认开启：挖方块的能力本应为接近源而动土；显式 false 只收窄通道，不改变目标筛选。
                            field("may_alter_terrain", "boolean", "While the mine family approaches its sources it may dig stairs, tunnels or pillar up through ordinary terrain; default true. Set false only to force ordinary walking routes. This never widens target selection: natural-tree checks, protected_labels and the do-not-break list still apply, and it grants no permission to break the requested sources' surroundings beyond the path."),
                            // 授权探矿后按实时通行断面开阶梯和水平通道；层位取生成带，方向与命中面交给原生形状执行。
                            field("allow_prospecting", "boolean", "After an empty fair source scan, permits opening a walkable descending passage to the known generation band and then a horizontal tunnel. FTB Ultimine uses native mining_tunnel for descending cuts and small_tunnel for horizontal cuts; material interruptions trigger live clearance repair, not fixed repetitions. Without native chain support the same passage is cleared block by block. Layer and excavation budgets are derived internally. Unknown generation bands decline prospecting. Default false."),
                            field("protected_labels", "array<string>", "Named entities, areas or possessions that must not be touched."),
                            field("radius", "integer", "Optional bounded loaded-world evidence radius; containers default to 32 from the fixed request origin, capped at 32, with explicit smaller radii respected."),
                            field("max_distance", "integer", "Hunt-only: frontier search radius from the request origin, 16..2048, default 512. Bounds how far a hunt may carry the body; set it low (e.g. 64) at night or low health. Ignored by non-hunt sources."),
                            field("source_hint", "object", "Optional semantic source evidence object with exactly these keys: block_ids (source block variants), block_tags (block tags, leading # optional), entity_type_ids (entity type families such as minecraft:cod), expected_item_ids (requested items the source is expected to yield), trade_profession_ids (villager profession families), description (short relationship string). Never coordinates, routes, entity IDs, slots or clicks. Hunting requires entity_type_ids together with expected_item_ids naming the requested item as the drop, e.g. {entity_type_ids:['minecraft:cod'],expected_item_ids:['minecraft:cod']}; a hunt without both keys selects no entity. Hunt in daylight at full condition: the frontier search may carry the player far from the start point (default 512), which is lethal at night or at low health; max_distance bounds that radius, and the search progress is visible in task progress.")));
            case "maicraft:build", BuildDesignAdapter.ABILITY -> contract(
                    BuildDesignAdapter.ABILITY.equals(ability)
                            ? "Execute this ability to display an authored scene, explicit blueprint or frozen project as a read-only preview, even with Dev off. Supply scene, scene_id, blueprint or a saved project_id; natural-language outcomes do not generate geometry. No movement, acquisition or construction is permitted. Unloaded model cells fail without exploration. Local confirm cannot start construction; use maicraft:build separately. plan alone validates and stores the Goal."
                            : "Construction requires an LLM-authored scene, saved scene_id or explicit block blueprint. Natural-language outcomes alone cannot build. Model operations stay inside this build ability: create_scene, update_scene, get_scene_info, get_object_info, get_component_info, preview, export_scene, revise_project and build. Only build changes the Minecraft world. Keep named objects, transforms, material slots and Boolean modifiers like Blender; the Mod voxelizes and executes the final cells. This is a declarative modelling subset, not a Python/bpy interpreter. Reuse scene_id for edits/preview/build and project_id for interrupted construction; never replan current_place to resume.",
                    targets("area", "landmark", "coordinates", "current_place", "prior_result"),
                    fields(
                            // 编辑产生新模型后，可明确把同一坐标范围的修订交给旧项目；采用修订与真正施工分开。
                            field("expected_capability_revision", "string", "Optional scene-operation guard from maicraft://building/index revision. Checks the current compiler contract and any saved scene's validation revision before saving, editing, previewing or building. Missing/outdated saved revisions require reading the full scene and create_scene revalidation into a new ID. Omit to preserve legacy behavior; project_id-only continuation uses its frozen targets instead."),
                            field("expected_design_schema_revision", "string", "Optional scene-operation guard from maicraft://building/index design_schema_revision; checks both current format contract and saved scene metadata. create_scene/update_scene results return capability_revision and design_schema_revision with the immutable scene_id. Neither revision proves site feasibility or completed construction."),
                            field("operation", "string", "Model operation: create_scene, update_scene, get_scene_info, get_object_info, get_component_info, preview, export_scene, revise_project or build. Omitted operation defaults to build for maicraft:build and preview for maicraft:design_build; a scene, scene_id or blueprint is still required. revise_project adopts a direct child scene revision for an idle project, preserving its policies and verified scaffolds; it changes no world blocks. Use only project_id to resume a frozen blueprint without a model operation. These run through plan/execute; operations other than build never start body work."),
                            // 模型先展开再施工；孔洞只约束所属对象，独立格栅保留，实体间覆盖和世界中的拆除权限分别判断。
                            field("scene", "object", "LLM-authored scene; v1 cube/panel semantics remain supported. v2: {schema_version:2,coordinate_system:'minecraft_y_up',materials:{Wall:{block_id:'minecraft:oak_planks'}},components:{Column:{objects:[{name:'Shaft',type:'MESH',primitive:'cube',location:[0.5,1.5,0.5],dimensions:[1,3,1],material:'Wall'}]}},objects:[{name:'Columns',type:'INSTANCE',component:'Column',location:[0,0,0],array:{count:[4,1,1],step:[3,0,0],skip:[[1,0,0]]}}]}. MESH and INSTANCE allow array, quarter-turn rotation_euler on all axes and mirror:['x']; INSTANCE also supports material_map. MESH supports triangle, wedge, triangular_prism, tetrahedron/triangular_pyramid, pyramid, prism, cylinder, cone, convex_polyhedron, fill:solid/hollow, wall_thickness, open_faces, face_materials, edge_material/edge_materials and edge_width. A panel may use pattern:{axes:['x','y'],rows:['01','10'],materials:{'1':'Upper','0':'Lower'}}; materials is optional, 1 defaults to the panel's painted material and 0 to an air hole. Either digit may start a row. Rows tile from the local minimum corner along the two Minecraft Y-up local axes, including Blender scenes; the remaining dimension must be 1. Named pattern materials follow instance material_map and block-state transforms; use slab properties.type top/bottom for alternating halves. Patterned panels may be cut but cannot serve as solid Boolean cutters. v2 block_state_axes defaults to local; minecraft_world preserves world-facing states. "
                                    + "Object composition: Boolean-cut voids, hollow interiors and default pattern-0 holes add AIR only where no other object has claimed the cell; they never erase another object's solid geometry. Solids can fill these voids. Solid-solid overlaps use last_wins (default: later solids replace earlier solids), or error to reject conflicting materials/states. For a circular grille, use last_wins and list the patterned panel before a wall cut by a cylinder at the same plane: later wall solids cover the rectangular grille outside the circle, while the wall's circular void preserves the grille inside. These model-object rules do not grant permission to replace existing world blocks; replace_existing is still required. "
                                    + "Blender coordinates remain supported. Root objects and their referenced definitions generate geometry; unused component definitions do not build. Cycles, invalid nested fields, material/face names and expansion budgets reject before construction. See blueprint knowledge for complete conventions."),
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
                    "Light a selected area, defaulting to offhand torches and full actual block-light coverage at level 8. Move and place in reach, then verify and repair dark cells. For route-following lighting without replacing the current task, use auto_light.",
                    targets("area", "landmark", "coordinates", "current_place", "prior_result"),
                    fields(
                            field("radius", "integer", "Optional explicit player-authored geometric boundary. Do not invent one; when omitted MaiCraft progressively closes the connected component from the semantic landmark seed."),
                            field("minimum_light", "integer", "Required observed block-light threshold, 1-15."),
                            field("coverage", "string", "all (default), most, crop_growth or player_visibility. Only all requires every sampled walkable cell to reach the threshold. Use crop_growth for cultivated farmland/crops."),
                            field("block_id", "resource_id", "Optional preferred light-source item/block, never a placement instruction."),
                            field("light_preferences", "array<resource_id>", "Ordered aesthetic source preferences; carried alternatives remain usable."),
                            field("material_policy", "string", "Ordinary, storage_available or inventory_only; storage is inspected before automatic crafting."),
                            field("allowed_sources", "array<string>", "Permitted semantic supply sources; never slots, routes or cells."),
                            field("allow_harm", "boolean", "Explicit harmful acquisition permission; false unless the player grants it."),
                            field("protected_labels", "array<string>", "Remembered areas/possessions that placement must not touch."),
                            field("style", "string", "Auto, ground or unobtrusive; MaiCraft still chooses cells. Wall and hanging layouts are not yet supported."),
                            field("max_placements", "integer", "Optional explicit total placement budget; omit it to let measured coverage and convergence end the task."),
                            field("placement_preference", "string", "Safe-candidate tie-break after measured coverage gain: coverage_optimal (default), central_unplanted (crop_growth only) or unobtrusive; MaiCraft still chooses cells.")));
            case AutomaticLightingAdapter.ABILITY -> contract(
                    "Configure or inspect session-scoped automatic lighting without replacing or pausing the current task or taking manual controls. Disabled by default and after session reset; explicitly enable when lighting is needed. Disable at any time to stop new placements; already submitted actions only finish receipt observation. Uses carried torches in the offhand while automation owns the body; primary actions and rescue have priority. Never detours, acquires materials, breaks terrain or changes navigation. Checks block light at visited feet/eyes, waits for each native receipt and light propagation, and avoids repeating placement from one standing position. Success means configuration applied, not future coverage. Status reports actual dark cells, unloaded cells and placement outcomes; use light_area to repair a whole area.",
                    targets(), fields(
                            field("action", "string", "enable (default for an explicit auto_light request), disable or status. A standalone execute is immediate and leaves the current task attached; status never enables lighting."),
                            field("minimum_light", "integer", "Target block light 1-13, default 8; independent of daylight. Torches emit 14. High thresholds may be unreachable without detouring; reported gaps are not success."),
                            field("protected_labels", "array<string>", "Remembered regions whose blocks and supports must remain untouched; current task protections also apply.")));
            case "maicraft:connect_mechanical_power" -> contract(
                    "Connect two semantic mechanical networks while respecting axes, stress and protected terrain.",
                    targets("landmark", "area", "prior_result"),
                    fields(
                            field("source_label", "string", "Existing powered network or landmark."),
                            field("target_label", "string", "Remembered destination machine/structure/landmark, or a registered block ID such as create:chain_conveyor to select that exact device type inside the target region."),
                            field("belt_direction", "string", "Optional declared item movement direction: north, south, east or west, for a destination belt using auto or chain_conveyor. Routes target this direction; receipts compare native motion separately from power. Omit only when direction is not a design requirement."),
                            field("transmission", "string", "auto (default) compares routes; chain_conveyor requires 锁链传动轮; encased_chain_drive requires 链式传动箱. The legacy chain_drive alias means encased_chain_drive. This connection preserves existing blocks; explicit old-line removal uses modify_machine."),
                            field("allow_new_receiver", "boolean", "May terminate at the nearest authoritative endpoint evidence when that evidence is a verified empty receiver rather than a machine."),
                            field("material_policy", "string", "Ordinary, storage_available or inventory_only; applied after route investigation."),
                            field("allowed_sources", "array<string>", "Permitted semantic material sources; storage is tried before crafting."),
                            field("allow_harm", "boolean", "Explicit harmful acquisition permission; never inferred."),
                            field("protected_labels", "array<string>", "Remembered resources or areas that material acquisition must preserve.")));
            case WaitAbilityAdapter.ABILITY -> contract(
                    "Wait without inventing body work until an observable condition is true.",
                    targets("current_place"),
                    fields(
                            field("condition", "string", "elapsed (default), day, night, health_full or not_hungry. Observe only; does not eat, heal or change time."),
                            field("after_s", "integer", "Minimum game-time seconds before checking the condition, 0-3600, default 1. Not a timeout; fractions and out-of-range values are rejected.")));
            case "maicraft:sequence" -> contract(
                    "Run semantic child goals in order; each child remains independently observable and recoverable, while explicit area protection can span later children. A direct child may declare on_failure=continue: if that step fails with a confirmed failure, the next sibling still runs, yet the whole sequence still ends failed — declare it only when later steps truly do not depend on this one; timeouts and cancels always stop. Explicitly skipped steps are marked skipped rather than successful. Finishing the remaining work may report all_steps_succeeded=false; inspect skipped_step_count, tolerated_failure_count and prior attempts for unresolved or partial effects.",
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
                // 物品允许按原料与工艺接收端分开声明，避免为满足端口数限制而强迫建造中心库存。
                + "At most 64 inputs total; non-item media allow three each with reasons for separate networks. Multiple item inputs require resource or reason. "
                + "Item inputs bind existing consumers without adding containers/transporters; supply via native manual interaction or explicitly authored transport. "
                + "These declarations require actual transfer verification. supply_preference defaults to external; "
                + "onsite requires onsite_reason explaining a deliberate local source. In survival, known creative-only "
                // 续建完整蓝图时复用场地内已存在的全部声明部件，不要求角色在背包里保留重复的一份。
                + "materials require real carried items, installed recipe evidence, or every declared block of that material already present at the bound site; acquisition remains separate. "
                + "Build first, then inspect; connect_external_input supports kinetic/energy, not item routing. Kinetic inputs may compare nearby loaded sources when source_label is omitted. "
                + "run_production is separate. Read remembered ports from perceive(machines).";
    }

    private static JsonObject blueprintField() {
        return field("blueprint", "object", "Explicit authored machine: {schema_version:1,blocks:[{offset:[x,y,z],block_id:'namespace:id',properties?:{property:'value'}}],expected_output?:item_id,constraints?:{forbidden_mods:[namespace]},assembly?:{installations:[{type:'create:belt',first:[x,y,z],second:[x,y,z]}],processing:[{processor:[x,y,z],surface:[x,y,z]}]}}. Include expected_output for a product goal; reusable workstations may omit it. Read maicraft://knowledge/machine_assembly for schema and native rules. Belt endpoints must be declared shafts; the native connector creates the belt, never individual belt-block placement. Omitted cells are preserved; required but occupied undeclared clearance is rejected. Configuration/production remain explicit native operations, not NBT writes. " + utilityInputs(true));
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

    /** 羊的条件既出现在能力发现中，也进入参数白名单；模型无需把颜色写进实体名字。 */
    private static JsonObject sheepFields(JsonObject fields) {
        JsonObject traits = fields(
                field("sheep_color", "string", "Optional native dye color (white, black, light_gray, etc.; all 16 colors). Only matching sheep; never fall back to another color. Rechecked before new attacks or interaction."),
                field("sheep_baby", "boolean", "Optional sheep age filter; false requires an adult."),
                field("sheep_sheared", "boolean", "Optional sheep shearing filter; false requires wool present."));
        traits.entrySet().forEach(entry -> fields.add(entry.getKey(), entry.getValue()));
        return fields;
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
