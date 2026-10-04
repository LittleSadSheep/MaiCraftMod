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
            tips.add("随行补光默认关闭。挖矿或找矿需要照明时先备好火把，用完整 plan 请求 {\"goal\":{\"ability\":\"maicraft:auto_light\",\"outcome\":\"沿当前采集路线补光\",\"parameters\":{\"action\":\"enable\",\"minimum_light\":8}}} 规划，再 execute 返回的真实 plan_id；单独执行该能力不会替换当前采集任务。开关成功不代表已换副手，后续暗处空闲刻才用原生交换把一叠火把放入副手，原副手物品回到背包。主动作忙、在水中、缺支撑时让位，缺火把只报告，不采购或绕路；可随时执行相同能力的 parameters.action=disable 停止新放置。");
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
                    // 按当前读图、整机比较和原生补读的实际分工说明；接受但未生效的兼容参数必须写明，不能诱导重复勘测。
                    "Read the present state of an existing fixed machine or an observed physical structure. This is an observation task: it does not travel, open menus, place/break blocks, supply materials, repair differences or prove production. Formal game requests require a confirmed MaiCraft server session. Put inspection options in goal.parameters, target beside parameters, and leave preferences empty. plan registers the goal; execute with its returned plan_id starts the observation. "
                            + "Fixed-machine selection: provide an observed machine_id and omit target, or use target.kind=current_place, coordinates, landmark, area or prior_result. coordinates requires position={x,y,z,dimension?} with integer block coordinates; omitted/null dimension means the current dimension. landmark/area require a remembered label. prior_result requires relation and a uniquely resolved successful earlier step; otherwise correct the goal. Explicit targets in another dimension are rejected, while a recorded machine in another dimension returns unknown map cells. Current code accepts machine_id together with target but uses the record; do not send conflicting selectors. "
                            + "full is the default. as_built_blueprint reads actual client-world block IDs, complete block-state properties and relative offsets, never design values substituted for unknown cells. With a usable record, full also returns the entire blueprint_diff and operating_state. An explicit radius changes only map capture and the local survey, not the recorded target comparison or registered component selection. Loaded full inspections create a new snapshot_id and remember the location and observed access points; unloaded centers return available facts and unknowns without a new snapshot_id. "
                            + "diff compares all recorded declared targets and reads their operating state without a new surrounding survey, server inventory scan or snapshot_id. Unavailable design references return blueprint_diff.available=false; task success alone does not mean a comparison was possible. Difference rows contain expected, actual, status and unknown reasons. Undeclared cells are not implicit air constraints, and structure_matches_blueprint never establishes production. Both modes retain uncertainty and do not trigger repair. "
                            + "Loaded full inspections optionally supplement the map through the negotiated machine.snapshot operation. The executor reads all reachable component/resource pages internally, within 16 blocks of the player, without walking closer; native rejections and missing fields remain explicit. Client geometry, operating state and server samples have their own ticks and are not an atomic snapshot. Do not sum overlapping sided resource views. native_processes matches the exact anchor only, not every block inside radius. "
                            + "Read results under machine; task queries may promote blueprint_diff and supply its direct detail paths. The public as_built_blueprint may use palette/cells instead of repeated blocks; it is an observation format, not a directly executable construction blueprint. Pause retains the same in-memory inspection; cancellation withdraws pending read requests. Current interrupted server collection can omit pages not yet merged at finish. World/account changes clear snapshot IDs; persistent machine records and completed historical results remain separate. Correct runtime selection errors with the returned replace_goal decision or cancel; resume restored tasks only after the world is bound. Reuse existing construction anchors and usable observations instead of surveying again just because time passed. "
                            + "Complete plan example: {\"goal\":{\"ability\":\"maicraft:inspect_machine\",\"outcome\":\"读取当前位置机器的布局与原生状态\",\"target\":{\"kind\":\"current_place\"},\"parameters\":{\"label\":\"现场机器\",\"mode\":\"full\"}}}. After that loaded inspection remembers the label, this valid request reads a diff or explicitly reports no recorded design: {\"goal\":{\"ability\":\"maicraft:inspect_machine\",\"outcome\":\"读取整机声明目标差异\",\"target\":{\"kind\":\"landmark\",\"label\":\"现场机器\"},\"parameters\":{\"mode\":\"diff\"}}}. Copy real machine/structure IDs from observations rather than inventing them.",
                    targets("current_place", "coordinates", "landmark", "area", "prior_result"),
                    fields(
                            field("label", "string", "goal.parameters.label: nonblank string, 1-160 UTF-16 code units. Fixed inspections choose this value, then the recorded label, then target.label; if none exists, supply one. Loaded full and diff remember it as a location; this does not rename the saved design. Omit for structure_id because that branch accepts but does not use label."),
                            field("machine_id", "string", "goal.parameters.machine_id: nonblank string, 1-80 UTF-16 code units. Copy recorded_machine.machine_id from a build receipt or perceive(view=machines). Omit target when using it; current code otherwise prioritizes this record. Lookup also accepts a unique current-dimension recorded label, but ambiguous labels require an ID. Missing/not-yet-loaded records cannot be inferred from an old snapshot_id."),
                            field("mode", "string", "goal.parameters.mode: case-sensitive full or diff, default full. full exports live layout and, for a usable record, also all recorded-target differences and operating state. diff returns only recorded-target observations without new snapshot_id or native inventory collection. Must be omitted with structure_id, even if full."),
                            field("offset", "integer", "goal.parameters.offset: exact integer 0-2147483647, default 0. In full it indexes all capture cells, including air/unknown, ordered X then Z then Y; not the non-air block array. At 0 the full capture is returned regardless of limit. A positive offset reads a fresh partial page. In diff it is accepted but ignored: all targets are compared from zero. Omit with structure_id."),
                            field("limit", "integer", "goal.parameters.limit: exact integer 1-512, default 256. Only limits full capture pages when offset>0; default full and all diff queries read the complete selected range/targets. Follow actual has_more/next_offset only for partial full pages. It never limits the executor's native component/resource collection. Omit with structure_id."),
                            field("radius", "integer", "goal.parameters.radius: exact integer 0-8 blocks, base default 4; 0 means the anchor cell. In full, explicitly supplying it overrides map capture with the inclusive (2r+1)^3 cube. Omit to use saved capture bounds; local survey expands from 4 up to 8 and reports bounds coverage separately. Without saved bounds use the radius cube. Does not restrict the full-record diff, operating_state or registered native targets. In diff accepted but unused. Omit with structure_id."),
                            field("component_offset", "integer", "goal.parameters.component_offset: exact integer 0-768, default 0. Compatibility starting index in this full inspection's relative_blocks for an unregistered machine; registered machines always traverse their declared component targets and ignore this start index. The executor automatically reads subsequent components. New inspections have new ordering/ticks. Ignored in diff; forbidden with structure_id."),
                            field("resource_offset", "integer", "goal.parameters.resource_offset: exact integer 0-4096, default 0. In full, starts the first selected component's native resource read at this offset; later components start at zero. Remaining native pages are read internally. Use a nonzero value only for a known unread resource cursor; it does not cap collection. Ignored in diff; forbidden with structure_id."),
                            field("structure_id", "string", "goal.parameters.structure_id: nonblank parseable UUID string, at most 36 characters, copied from a physical-structure observation such as perceive(view=situation,focus=maicraft:physical_structures). Requires ready native structure/pose/bounds. Omit target, machine_id, mode, radius, offset, limit, component_offset and resource_offset entirely; explicit zero still conflicts. Returns read-only control components/connections, unresolved inputs and vehicle classification, not fixed-machine full/diff or proof the structure can be driven. For all inspection parameters, null/false and numeric strings are invalid rather than defaults; omit optional values instead.")));
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
                    // 先说明实际目标解析、动作与确认边界，避免把观察、接单或靠近当成已达成玩家目标。
                    "Find a required count of distinct currently visible, loaded entities; success is observation, not capture, permission to harm or collected drops. Merge entity_type_ids and entity_type_id, deduplicate, require 1..32 registered types. If neither supplies types, entity.label may supply a registered type, never a display name. The search starts at the current body location. Accepted area/landmark targets do not cause travel or bind the search origin; travel separately first. nearest adds no nearest-identity output guarantee. Scan with direct line of sight, relation, sheep and protection checks; if insufficient, walk bounded first-person frontiers and rescan while moving. count is a total across accepted types and must be present in the same current scan, not accumulated from past visits. wild means non-hostile Mob; hostile means Enemy or MONSTER category; unowned starts with non-player living candidates; any has no relation filter. wild/unowned then exclude named, tamed, owned, leashed, vehicle-held or passenger-carrying entities. any/hostile do not prove unowned or attack permission. Entity-label protection currently requires a resolved same-dimension MANAGED_SETTLEMENT landmark within 12 horizontal blocks plus actual enclosure evidence for wild/unowned; unknown labels are not proof of protection. Inspect verified, observed_acceptable_count/by_type, observed_sheep, failure_code, protection_reason_counts and frontier progress. Partial counts fail; exhausted exploration does not prove worldwide absence. Recover by moving the starting place, increasing the bounded distance or changing an explicitly authorized route; never turn partial evidence into success or invent an entity handle for follow/combat.  Optional sheep_color is one exact lowercase native dye name: white, orange, magenta, light_blue, yellow, lime, pink, gray, light_gray, cyan, purple, blue, brown, green, red or black. sheep_baby and sheep_sheared require JSON booleans: false means adult and wool present respectively, not disabling the filter. Omission leaves that trait unrestricted; null, wrong types and unknown colors are rejected. Any sheep trait restricts candidates to sheep.  Put fields in goal.parameters, not goal.preferences or outcome text. No ability-specific preferences or hard constraints are declared. Omit goal.target or use a declared target kind. Do not submit runtime entity IDs, UUIDs, click scripts or weapon slots. Integer inputs currently use defaults for omitted/null/unreadable values and clamp readable out-of-range values; 0 becomes the lower bound, not disabled. Send actual JSON integers and booleans; ordinary switches default false when omitted/null. Planning does not perform the action: execute the goal or returned real plan_id. Acceptance is not completion. Inspect the real task receipt; pause/resume use task actions, while a pending decision requires task action=answer with the returned decision_id. Use its retry option with details.parameters or recover/replace_goal with details.goal; unknown prior effects may prohibit ordinary retries. Cancel does not undo native effects. Restored nonterminal semantic work is paused and re-resolves current targets; old runtime identities are not a promise across world/body changes.  Complete plan request example: {\"goal\":{\"ability\":\"maicraft:find_entity\",\"outcome\":\"找到两只仍带毛的成年白羊\",\"target\":{\"kind\":\"current_place\"},\"parameters\":{\"entity_type_ids\":[\"minecraft:sheep\"],\"relation\":\"wild\",\"count\":2,\"max_distance\":512,\"may_alter_terrain\":false,\"protected_labels\":[],\"sheep_color\":\"white\",\"sheep_baby\":false,\"sheep_sheared\":false}}}",
                    targets("entity", "nearest", "area", "landmark", "current_place"),
                    sheepFields(fields(
                            field("entity_type_id", "resource_id", "Optional registered namespaced type string, merged with entity_type_ids; neither is mutually exclusive. If no parameter types remain, entity.label may supply a registered type. At least one valid type is required."),
                            field("entity_type_ids", "array<resource_id>", "Array of registered namespaced type strings; merged/deduplicated with entity_type_id, final 1..32 types. Omitted/null/non-array supplies no array entries; empty array can only work with another valid type source. Invalid entries/types request a decision."),
                            field("relation", "string", "String wild|hostile|unowned|any (case normalized). Parameter overrides target.relation; absent/null/blank falls through, then defaults any. Unsupported values request a decision. wild/unowned require observed ownership/protection exclusions; any/hostile do not imply harm permission."),
                            field("count", "integer", "Integer required distinct current visible matches across all requested types, default 1, clamped 1..32 (0 becomes 1). Old observations leaving view do not accumulate; partial current count is failure."),
                            field("max_distance", "integer", "Integer horizontal blocks from body position at search start, default 512, clamped 16..2048 (0 becomes 16). Entity evidence stays within this origin circle; body movement beyond radius+8 tolerance fails. Not a target-area anchor or lifetime guarantee."),
                            field("may_alter_terrain", "boolean", "Boolean default false; omitted/null/false uses routes without terrain alteration. True permits the navigator to dig/place within native route policies, not arbitrary destruction. Supply in goal.parameters, not undeclared preferences."),
                            field("protected_labels", "array<string>", "Array of nonblank remembered label strings, default [] when omitted; [] imposes no extra labels, null/non-array/bad entries rejected, at most 64 distinct normalized labels. Entity protection currently combines resolved MANAGED_SETTLEMENT context and actual enclosure for wild/unowned; it is not automatic exclusion of every named area. Separate upper-level measured area protections may apply."))));
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
                    // 先说明实际目标解析、动作与确认边界，避免把观察、接单或靠近当成已达成玩家目标。
                    "Fight selected loaded entities, or defend dynamically against actual threats. All explicit combat requests, including defend, require allow_harm=true. With no selector, mode=defend (legacy defence) starts dynamic self-defense; count/radius do not restrict that branch. With a selector it uses named-target combat. engage and defeat do not implement different strategies; omitted or other mode strings have no separate strategy/rejection branch. count is a maximum, not a required kill quota: choose up to that many currently matching entities. count=1 with several matches needs nearest or a narrower selector; count>1 takes the nearest subset. Players, named/tamed entities and non-Enemy targets additionally need confirm_risky_target=true for the resolved target. Ordinary explicit combat is not strict-target-only: actual extra attackers may interrupt it. The body selects weapons, positions, aims, waits native cooldowns and settles attacks; it may retreat or evade blasts. Bow/crossbow actions use arrow ballistics, not generic mod gun support. Requested targets need death evidence; disappearance is not a kill. Non-player combat may continue through causal loot settlement. Read completion, defeated_requested_targets, lost_targets, unreachable_targets and loot_receipt. strikes combines confirmed melee receipts with ranged releases, not total hits. last_ranged_shot describes only the final attempt; cancelled preparation can coexist with earlier successful shots. Changed requested sheep traits stop further attacks and are reported, never silently substituted.  Type/name selectors combine. target.kind=player uses label as player_name when absent; entity.label is a registered entity type or otherwise a display name. A registered entity.label can override entity_type_id, so do not provide contradictory selectors. nearest alone supplies no entity filter. Initial matching checks loaded alive objects in the player bounding box inflated along each axis by radius, not a strict sphere and not line-of-sight visibility. No matching target requests a decision; it does not explore. Names are compared case-insensitively; display names need not be unique.  Optional sheep_color is one exact lowercase native dye name: white, orange, magenta, light_blue, yellow, lime, pink, gray, light_gray, cyan, purple, blue, brown, green, red or black. sheep_baby and sheep_sheared require JSON booleans: false means adult and wool present respectively, not disabling the filter. Omission leaves that trait unrestricted; null, wrong types and unknown colors are rejected. Any sheep trait restricts candidates to sheep.  Put fields in goal.parameters, not goal.preferences or outcome text. No ability-specific preferences or hard constraints are declared. Omit goal.target or use a declared target kind. Do not submit runtime entity IDs, UUIDs, click scripts or weapon slots. Integer inputs currently use defaults for omitted/null/unreadable values and clamp readable out-of-range values; 0 becomes the lower bound, not disabled. Send actual JSON integers and booleans; ordinary switches default false when omitted/null. Planning does not perform the action: execute the goal or returned real plan_id. Acceptance is not completion. Inspect the real task receipt; pause/resume use task actions, while a pending decision requires task action=answer with the returned decision_id. Use its retry option with details.parameters or recover/replace_goal with details.goal; unknown prior effects may prohibit ordinary retries. Cancel does not undo native effects. Restored nonterminal semantic work is paused and re-resolves current targets; old runtime identities are not a promise across world/body changes.  Complete plan request example: {\"goal\":{\"ability\":\"maicraft:combat\",\"outcome\":\"击败附近最近的僵尸\",\"target\":{\"kind\":\"nearest\"},\"parameters\":{\"entity_type_id\":\"minecraft:zombie\",\"mode\":\"defeat\",\"selection\":\"nearest\",\"count\":1,\"radius\":32,\"allow_harm\":true,\"confirm_risky_target\":false}}}",
                    targets("entity", "player", "nearest"),
                    sheepFields(fields(
                            field("mode", "string", "Optional string: defend (legacy defence) without a selector uses dynamic self-defense. With selectors all modes select targets; engage/defeat/omitted have no distinct strategy. Unknown strings are not explicitly rejected. No-selector defend does not apply count or radius."),
                            field("entity_type_id", "resource_id", "Optional registered namespaced type string. Omitted/null/blank supplies no type. Combines with player_name/entity_name and sheep traits; invalid registered IDs cause a decision, never an any-entity fallback."),
                            field("entity_name", "string", "Optional case-insensitive exact display/custom name, not a unique identity. Omitted/null/blank adds no name filter; several matches may need nearest or narrower selectors."),
                            field("player_name", "string", "Optional case-insensitive exact player profile name; omitted/null/blank adds no player filter. Player combat also requires both harm and risky-target confirmations."),
                            field("selection", "string", "Optional string; nearest permits any nearest matching target. Omitted/null/other strings do not resolve ambiguity; target.kind or target.relation=nearest also enables it and is not overridden by selection=unique."),
                            field("count", "integer", "Integer maximum selected targets, default 1, clamped 1..20 (0 becomes 1). Fewer loaded matches are allowed; count>1 selects nearest subset without requiring selection. Not a minimum kill quota; unused in no-selector defend."),
                            field("radius", "integer", "Integer blocks for initial loaded bounding-box scan, default 32, clamped 4..128 (0 becomes 4). Not a strict spherical radius, weapon range, pursuit bound or timeout; unused in no-selector defend."),
                            field("allow_harm", "boolean", "Boolean must be true for every explicit combat request including defend. Omitted/null/false requests a decision and does not dispatch attack. Reflect already authorized user intent."),
                            field("confirm_risky_target", "boolean", "Boolean default false (omitted/null/false). Required true in addition to allow_harm when selected targets include players, named/tamed entities or non-Enemy creatures; confirm this semantic target, not blanket nearby harm."))));
            case GeneralAbilityAdapter.INTERACT -> contract(
                    // 契约按实际目标分流、原生确认和已知恢复边界说明，示例不替调用者虚构现场证据。
                    "对一个方块或实体执行普通原生右键；适合按按钮、打开目标、登船、剪毛、使用已带物品，以及 Create 手摇曲柄。不是攻击、挖掘、菜单内交易或容器存取；这些目标应选对应能力。 "
                            + "请求层级：goal.ability=maicraft:interact，goal.outcome 描述目的；下面字段均在 goal.parameters。goal.target 可省略，或选 coordinates/entity/player/nearest/landmark/area。不要传内部 button、hold_ticks、槽位或运行期 entity_id。 "
                            + "coordinates 使用 goal.target.position={x,y,z,dimension?}，x/y/z 为整数方块格；dimension 省略或 null 取当前维度，异维度不执行。坐标只操作该格；满桶坐标是流体落格，可为空气，不是支撑块坐标。实体描述与方块类型或坐标不能混用。 "
                            + "非坐标方块需 block_id，省略目标或 nearest 时围绕玩家查找；landmark/area 的 position 或已记住同维度 label 作为搜索中心，解析失败不改为玩家附近。实体按类型、显示名、玩家名及羊属性取交集；entity 的 label 可作注册类型或显示名，player 的 label 可作玩家名。匹配多于一个时需 nearest 许可，否则待决策。 "
                            + "羊属性省略表示不筛对应特征；提供任一 sheep_* 即只选羊。sheep_baby=false 要成年，sheep_sheared=false 要有毛，两者必须是真布尔，null/0/字符串拒绝。sheep_color 必须为 white/orange/magenta/light_blue/yellow/lime/pink/gray/light_gray/cyan/purple/blue/brown/green/red/black；null/空串/错拼拒绝。靠近期间再次复核，不换另一只羊。 "
                            + "动作顺序：解析加载目标→选站位并走近→整理主手与界面→等待镜头和真实命中→提交原生动作→结算同一回执及现场变化。未指定道具通常准备空手；till 的自动备锄是例外。方块路线先尝试现有路，允许地形准备时才在失败后开路；实体分支目前未传递 may_alter_terrain。 "
                            + "满桶以物品自身射线倒入目标，空桶要求真实可取源格；普通方块和实体使用各自原生交互。超时未知不能当未提交重发；只有明确未生效且分支允许时才会转入物品后续使用。省略道具的空手操作不启用此回退。 "
                            + "execute 接单不表示效果完成；按 next_attention 等待。方块回执可含 native_action_kind/status、outcome_uncertain、target_observation、changes、interaction_approach，流体反应产物据实保留。实体回执目前主要提供实体/羊属性、变化和剪毛掉落数，未完整透传原生状态；目标消失后的 acted 成功分支也不能独立证明指定效果。剪下羊毛不等于已入包。 "
                            + "purpose=open 不额外保证 menu_open_verified；正时长曲柄报告原生转动和超载，machine_production_verified 仍为 false。缺物品、目标歧义或不可用位置按当前决策补充条件；已提交或不确定时先查原任务与相关现场，再决定余下动作。暂停保留逻辑状态但释放身体，取消不回滚副作用；死亡、换世界或磁盘恢复后先检查暂停记录，不复用旧实体编号或路线。 "
                            + "完整 plan 参数示例（坐标须替换为实际观察值）：{\"goal\":{\"ability\":\"maicraft:interact\",\"outcome\":\"打开最近的工作台\",\"target\":{\"kind\":\"nearest\"},\"parameters\":{\"block_id\":\"minecraft:crafting_table\",\"selection\":\"nearest\",\"radius\":32,\"purpose\":\"open\"}}} "
                            + "剪毛示例：{\"goal\":{\"ability\":\"maicraft:interact\",\"outcome\":\"给最近的成年有毛白羊剪毛\",\"parameters\":{\"entity_type_id\":\"minecraft:sheep\",\"sheep_color\":\"white\",\"sheep_baby\":false,\"sheep_sheared\":false,\"item_id\":\"minecraft:shears\",\"selection\":\"nearest\",\"radius\":32}}} "
                            + "已安装 Create 且观察到原生曲柄时的十秒手摇示例：{\"goal\":{\"ability\":\"maicraft:interact\",\"outcome\":\"空手摇曲柄并观察实际转动\",\"target\":{\"kind\":\"coordinates\",\"position\":{\"x\":10,\"y\":64,\"z\":10,\"dimension\":\"minecraft:overworld\"}},\"parameters\":{\"block_id\":\"create:hand_crank\",\"purpose\":\"use\",\"duration_seconds\":10}}} ",
                    targets("coordinates", "entity", "player", "nearest", "landmark", "area"), sheepFields(fields(
                            field("block_id", "resource_id", "goal.parameters.block_id：可选已注册命名空间方块 ID；非坐标方块搜索必需，坐标时可省略。提供时核对目标仍是该类型；省略/null/空白不提供类型条件。不得与实体筛选混用。"),
                            field("entity_type_id", "resource_id", "goal.parameters.entity_type_id：可选已注册实体类型 ID，不是运行编号；省略/null/空白不筛类型。与实体名、玩家名和羊属性取交集；候选必须已加载且存活，实际视线在操作前检查。"),
                            field("entity_name", "string", "goal.parameters.entity_name：可选非空显示名字符串，不区分大小写；省略/null/空白不筛名字。可能有同名对象；多个匹配仍需 nearest 许可或修订目标。"),
                            field("player_name", "string", "goal.parameters.player_name：可选非空玩家档案名字符串，不区分大小写；省略/null/空白不筛玩家名；可由 target.kind=player 的 label 提供，仍受加载与范围限制。"),
                            field("item_id", "resource_id", "goal.parameters.item_id：可选已注册随身物品 ID；显式提供时缺料待决策，不自动找料。省略/null/空白时准备空主手；purpose=till 时可自动选择并准备适用锄。"),
                            field("item_resource_id", "string", "goal.parameters.item_resource_id：可选观察所得组件身份字符串，仅方块使用并要求 item_id；内部记录非空且最长512字符。省略/null/空白不限定变体；点名后每次提交前核对，变化或消失停止。不可用于实体，不要构造身份值。"),
                            field("purpose", "string", "goal.parameters.purpose：可选字符串，省略/null/空白是普通右键。till 自动备锄并核对耕地，attack/break 返回改用战斗/采矿的决策。正 duration_seconds 必须 use；open/talk/trade 等其他值并不执行特定菜单流程，也不是严格枚举校验。"),
                            field("duration_seconds", "number", "goal.parameters.duration_seconds：可选 JSON 数字，单位秒，0..30；省略或0只做一次，null/false/数字字符串/越界拒绝。正值要求 purpose=use、无 item_id、无实体筛选且目标为原生 Create 手摇曲柄；ceil(seconds*20)游戏刻从建立交互时开始，走近不计时，已提交末次动作仍结算。"),
                            field("selection", "string", "goal.parameters.selection：可选字符串；nearest（忽略大小写）允许本轮匹配中的最近者。省略/null/空白时多个匹配待决策；其他值没有独立选择策略，不能撤销 target.kind 或 target.relation 的 nearest 许可。"),
                            field("radius", "integer", "goal.parameters.radius：推荐整数，单位格，4..128；非坐标方块默认64，实体默认48；省略/null/解析失败用默认，解析为int后夹到4..128，0因而变为4。当前解析可能截断小数、接受数字字符串或转换过大数值，调用方应传范围内整数。方块用加载索引及水平范围；实体用扩张身体包围盒，候选阶段不检查视线。精确坐标分支忽略此字段。"),
                            field("may_alter_terrain", "boolean", "goal.parameters.may_alter_terrain：布尔，省略/null/false默认不授权方块接近路线改地形；true允许普通路线失败后准备通路，目标仍固定。实体分支当前未传递此参数，不能用它要求实体导航开路。"))));
            case GeneralAbilityAdapter.USE_ITEM -> contract(
                    // 契约按实际目标分流、原生确认和已知恢复边界说明，示例不替调用者虚构现场证据。
                    "使用一个已带物品：定点分支会自动接近并对指定格使用；不带坐标则沿当前视线持用；批次按新增产物数量逐次操作。不要仅在 outcome 里写定点而省略 target。 "
                            + "请求层级：goal.ability=maicraft:use_item；字段均在 goal.parameters。goal.target 仅接受 current_place 或 coordinates，也可省略。coordinates 使用 target.position={x,y,z,dimension?}，三轴整数单位格，dimension省略/null为当前维度；不支持实体目标和异维度操作。 "
                            + "坐标目标只做一次，count必须1且不允许 ingredient_item_id：满桶的坐标是流体落格（可空气），空桶是取源格，末影之眼是被嵌的门框。Mod选支撑面、走近、选物、转头并复核真实射线；桶走原生物品使用，门框嵌眼走方块使用，不要求调用方给点击面。 "
                            + "省略 target 或 current_place 不重新瞄准目标；原生物品可以影响当前视线下的世界。物品已在主手时优先用主手，其次可用已持有的副手，再从库存选择。count=1且没有原料时保留当前双手；count>1或提供原料时进入批次。批次有原料时先备副手、再备主手工具，每次完成后检查产物并从随身库存续料或换工具，不自动去仓库或采集合成。 "
                            + "批次需要 expected_output_item_id，工具、原料（若有）和产物必须是不同物品类型。count按新增产物计，不是点击次数；一次多产可以超过目标。标称原生持用时长超过1200游戏刻的物品会要求专门活动，这不是全部任务的统一超时。 "
                            + "先记录产物库存，再提交一次原生动作，等待同一确认；声明产物后额外等待最多40游戏刻同步。当前确认采用同物品类型库存增加，未按组件或产物来源严格归因，并发拾取可能影响增量。不得把返空桶或动作成功冒称目标流体仍保持原样。 "
                            + "execute返回的是接单；按next_attention等候。定点回执用target_observation呈现实际前后状态，可含native_action_kind/status、outcome_uncertain与changes；声明返还物时有expected_output.before/after/observed_increase。无坐标单次还有held_item_use_started/native_use_completed/used_hand；批次返回phase、target_output_count、completed_output_count、completed_uses、remaining_output_count和last_step。 "
                            + "批次遇缺料、未确认或任何stop（包括暂停/抢占）都会停止；恢复同一批次不会无缝续做，而是报告中断，mechanical_retry_allowed=false。先核对已完成、剩余与未知效果再决定新目标。单次暂停保留逻辑状态；取消不回滚副作用。死亡、换世界或重启后先读暂停记录与真实背包，不重放未知一次。 "
                            + "完整plan参数示例（坐标替换为实际观察值；满桶落格而非支撑块）：{\"goal\":{\"ability\":\"maicraft:use_item\",\"outcome\":\"把熔岩倒入指定格并观察返桶\",\"target\":{\"kind\":\"coordinates\",\"position\":{\"x\":10,\"y\":64,\"z\":10,\"dimension\":\"minecraft:overworld\"}},\"parameters\":{\"item_id\":\"minecraft:lava_bucket\",\"expected_output_item_id\":\"minecraft:bucket\",\"may_alter_terrain\":false}}} "
                            + "定点嵌眼示例：{\"goal\":{\"ability\":\"maicraft:use_item\",\"outcome\":\"给指定门框嵌眼\",\"target\":{\"kind\":\"coordinates\",\"position\":{\"x\":10,\"y\":64,\"z\":10,\"dimension\":\"minecraft:overworld\"}},\"parameters\":{\"item_id\":\"minecraft:ender_eye\",\"block_id\":\"minecraft:end_portal_frame\"}}} "
                            + "非坐标批次示例（需携带足够蜜瓶，真实饮用并累计新增玻璃瓶）：{\"goal\":{\"ability\":\"maicraft:use_item\",\"outcome\":\"使用蜜瓶获得三只新增玻璃瓶\",\"target\":{\"kind\":\"current_place\"},\"parameters\":{\"item_id\":\"minecraft:honey_bottle\",\"count\":3,\"expected_output_item_id\":\"minecraft:glass_bottle\"}}} ",
                    targets("current_place", "coordinates"), fields(
                            field("item_id", "resource_id", "goal.parameters.item_id：必需已注册随身物品ID；省略/null/空白要求补充，不沿用任意当前手持物；缺料待决策。批次工具与原料、产物必须互异。没有 item_resource_id 变体选择参数。"),
                            field("block_id", "resource_id", "goal.parameters.block_id：可选已注册原方块ID，仅坐标分支读取并在提交前核对。省略/null/空白不提供显式类型条件；满桶通常省略，以便空气变流水后按现场尝试。在current_place提供它目前不会选中或验证方块。"),
                            field("may_alter_terrain", "boolean", "goal.parameters.may_alter_terrain：布尔，仅坐标目标的接近路线使用；省略/null/false默认不改地形，true在普通路线失败后允许准备通路，不改变落格。无坐标分支忽略该字段。"),
                            field("ingredient_item_id", "resource_id", "goal.parameters.ingredient_item_id：可选已注册随身原料ID；省略/null/空白不要求副手准备。非空即启用批次，即使count=1也需要expected_output_item_id；装备副手原料与主手工具，原生物品必须支持这种配对。坐标目标禁止提供。"),
                            field("count", "integer", "goal.parameters.count：JSON数字且数值必须精确为整数1..64，省略为1；0/null/false/非整数数值/数字字符串/越界拒绝。非坐标批次以新增产物件数为目标，非使用次数；坐标目标只能1。一次原生使用多产时完成量可超过count。"),
                            field("expected_output_item_id", "resource_id", "goal.parameters.expected_output_item_id：已注册且非空气的产物或返还物ID；单次可省略/null/空白，count>1或提供ingredient_item_id时必填。每次完成后要求同类型库存增加，最多再等40游戏刻；按库存增量而非组件/来源归因。定点倒桶可用minecraft:bucket检查返还，坐标未提供时不验证任何指定方块产物。")));
            case GeneralAbilityAdapter.HARVEST_BLOCK -> contract(
                    "Harvest one exact observed resource block through native breaking and pickup. Stops after one source break even if it regenerates; reports source position and carried output increase. Approaches without altering surrounding terrain. The cell directly underfoot cannot be targeted — a zero-distance stance generates no approach route. No inventory-source substitution.",
                    targets("coordinates"), fields(
                            field("block_id", "resource_id", "Required observed source block; the loaded state must still match. Block entities and fluids are excluded."),
                            field("expected_output_item_id", "resource_id", "Required expected drop; success requires this carried output to increase after a confirmed source break."),
                            field("may_alter_terrain", "boolean", "Required true for breaking this one cell, including its native neighbor updates. Does not permit breaking surrounding structures.")));
            case GeneralAbilityAdapter.FOLLOW -> contract(
                    // 先说明实际目标解析、动作与确认边界，避免把观察、接单或靠近当成已达成玩家目标。
                    "Continuously follow one selected loaded entity. It is a standing task with no normal SUCCESS or deadline; do not put it before a sequence step that must run automatically. Choose a unique match or explicitly permit nearest. The internal task freezes runtime ID plus UUID and does not silently follow a replacement ID. Begin moving beyond distance+2 blocks; stop movement within distance. Both use 3D entity positions. For airborne targets navigation may anchor up to 64 blocks below, while distance still measures the entity: being under it may not satisfy the stop condition. Missing/removed or UUID-mismatched targets and failed routes end with failure. Death before removal is not separately checked by target(). Default routes do not alter terrain; explicit may_alter_terrain uses permitted terraforming, and failure may report required changes without guaranteeing a complete list. Following does not imply combat, protection, taming, mounting or cross-dimension reacquisition. Sheep trait parameters and count are not declared for follow.  Type/name selectors combine. target.kind=player uses label as player_name when absent; entity.label is a registered entity type or otherwise a display name. A registered entity.label can override entity_type_id, so do not provide contradictory selectors. nearest alone supplies no entity filter. Initial matching checks loaded alive objects in the player bounding box inflated along each axis by radius, not a strict sphere and not line-of-sight visibility. No matching target requests a decision; it does not explore. Names are compared case-insensitively; display names need not be unique.  Put fields in goal.parameters, not goal.preferences or outcome text. No ability-specific preferences or hard constraints are declared. Omit goal.target or use a declared target kind. Do not submit runtime entity IDs, UUIDs, click scripts or weapon slots. Integer inputs currently use defaults for omitted/null/unreadable values and clamp readable out-of-range values; 0 becomes the lower bound, not disabled. Send actual JSON integers and booleans; ordinary switches default false when omitted/null. Planning does not perform the action: execute the goal or returned real plan_id. Acceptance is not completion. Inspect the real task receipt; pause/resume use task actions, while a pending decision requires task action=answer with the returned decision_id. Use its retry option with details.parameters or recover/replace_goal with details.goal; unknown prior effects may prohibit ordinary retries. Cancel does not undo native effects. Restored nonterminal semantic work is paused and re-resolves current targets; old runtime identities are not a promise across world/body changes.  Complete plan request example: {\"goal\":{\"ability\":\"maicraft:follow\",\"outcome\":\"跟随最近的牛且不改地形\",\"target\":{\"kind\":\"nearest\"},\"parameters\":{\"entity_type_id\":\"minecraft:cow\",\"selection\":\"nearest\",\"radius\":64,\"distance\":3,\"may_alter_terrain\":false}}}",
                    targets("player", "entity", "nearest"),
                    fields(
                            field("entity_type_id", "resource_id", "Optional registered namespaced type string, combines with name selectors. Omitted/null/blank adds no type filter. At least one target type/name is needed; no automatic owner lookup."),
                            field("entity_name", "string", "Optional case-insensitive exact custom/display name; omitted/null/blank adds no filter. A non-unique name requires narrower selection or explicit nearest."),
                            field("player_name", "string", "Optional case-insensitive exact player profile name, also available through target={kind:player,label:observed_name}. Use an observed name, not a runtime ID or UUID."),
                            field("selection", "string", "Optional string nearest to choose nearest matching loaded candidate. Omitted/null/other values do not resolve multiple matches; target.kind or target.relation=nearest also authorizes nearest selection."),
                            field("distance", "integer", "Integer blocks, default 3, clamped 2..16 (0 becomes 2). Stops within this 3D entity-position distance and resumes beyond distance+2. Omitted/null/unreadable uses default; not a route or airborne target ground height."),
                            field("radius", "integer", "Integer blocks, default 64, clamped 4..128 (0 becomes 4), for initial loaded bounding-box scan only. Not a maximum later follow distance; no visibility ray is checked at selection."),
                            field("may_alter_terrain", "boolean", "Boolean default false (omitted/null/false). True enables permitted route terraforming to keep up; does not guarantee reachability. Cancellation preserves blocks already changed.")));
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
            // 模型选择类型或真实掉落引用；执行器负责接触、等待和核验，范围扫空不能冒充点名物品已入包。
            case GeneralAbilityAdapter.COLLECT -> contract(
                    "Collect loaded loose item entities through native contact pickup; this is not a final inventory-count request or a backpack/container withdrawal. "
                            + "Place drop_ref, item_ids, radius and may_alter_terrain in goal.parameters. None is required: {} sweeps all loaded item types. "
                            + "goal.outcome describes the purpose but cannot set a quantity, route or selector. Omit goal.target, use null, or use {\"kind\":\"current_place\"}; do not attach a location label, position or relation. "
                            + "This ability declares no specific preferences or hard constraints; use {} and [] or omit them. Runtime-wide death authorizations are handled by the outer runtime. count, item_id, tags and click scripts are not collection parameters. "
                            + "Copy drop_ref verbatim from an existing perceive(view=surroundings,sections=[nearby_entities]) item observation. It binds one dimension and UUID; do not invent a reference or substitute a numeric entity ID. "
                            + "drop_ref and item_ids are compatible and intersect. The selected stack must initially be loaded, inside the scan box and of an accepted type. An absent or mismatching selected stack fails as unconfirmed instead of selecting another stack. "
                            + "Each scan expands the actor current body box by radius on x/y/z, not a sphere or a fixed site. Once selected, the live entity is followed even beyond the original scan radius. "
                            + "The same task scans, chooses a contact stance, navigates, waits for native pickup cooldown and inventory synchronization, records confirmed units, then scans again. No separate travel or break request is required for these normal phases. "
                            + "may_alter_terrain=true permits native route digging, bridging or pillaring subject to clearance whitelist, protections, materials and actual native conditions. A proposed clearable stance is not an executed route. False or omission uses existing routes. Confirmed terrain changes are retained on failure or cancellation. "
                            + "The initial lease is 1200 game ticks and may extend with actual progress. Pickup synchronization and selected-packet waits use 20-game-tick windows. No-path/terrain-blocked retries remain inside this task, spaced by 10 ticks within a 40-tick window from the first failure for that UUID. "
                            + "For a public drop_ref, completion requires entity disappearance, matching item-and-component main-inventory gain, and this player same-UUID native pickup packet; partial confirmed units are retained separately from whole-stack completion. "
                            + "Unselected sweeps use disappearance plus matching inventory gain without the extra same-UUID packet requirement. They can succeed with collected=0 when no candidates remain. Their statistics need not cover an in-progress partial pickup on interruption or every incidental native pickup. "
                            + "Native contact may absorb neighboring items outside the requested filter. Do not interpret collected_items as a complete inventory diff or exclusive ownership proof. "
                            + "Read collected, collected_items, radius, pickup_navigation.may_alter_terrain, pickup_navigation.confirmed_terrain_changes, unreachable_drop_stacks, disappeared_without_inventory_receipt, pickup_rejected_after_contact and last_uncollected_detail. "
                            + "For selected identities, drop_collection includes requested_drop_refs, collected_drop_refs, unconfirmed_drop_refs and observations with last observed position, count, time, cooldown and component facts. Missing observation is unknown, not zero. "
                            + "An old UUID disappearing in a merge is not automatically collected: retargeting requires an already observed same-component survivor that remains permitted, so a single public drop_ref does not authorize a new survivor UUID. "
                            + "Accepted means registered, not delivered. Follow next_attention or task get; use returned detail_path with the same task_id for retained evidence, never repeat execute merely to recover output. "
                            + "Pause/resume retains live task progress while releasing controls. Short pickup/retry windows use raw world game time and are not all frozen by pause. Cancellation preserves actual effects. Death/world changes and restored checkpoints do not prove collection or restore old native routes. "
                            + "If an actual decision is pending, answer its current decision_id and listed choice; ordinary pause uses resume, terminal failure cannot be resumed. Re-observe only needed entities when a reference is lost, inspect remaining items and route effects, then submit a revised goal. "
                            + "Complete plan argument example: {\"goal\":{\"ability\":\"maicraft:collect_items\",\"outcome\":\"拾取附近地上的黑曜石\",\"target\":{\"kind\":\"current_place\"},\"parameters\":{\"item_ids\":[\"minecraft:obsidian\"],\"radius\":16,\"may_alter_terrain\":false},\"preferences\":{},\"constraints\":[]}}. "
                            + "For an authorized clearance example, keep the same complete request and set goal.parameters.may_alter_terrain=true; for a selected stack add the actual observed drop_ref, never a fabricated UUID.",
                    targets("current_place"),
                    fields(field("drop_ref", "string", "Optional full dimension|UUID string copied from nearby_entities. Omit for type/all-items collection. Null, empty or malformed strings are rejected; another dimension is rejected when creating the task. The stack must initially be loaded inside radius and match item_ids if supplied; its current position is then followed. The reference selects entity identity, not a requested quantity."),
                            field("item_ids", "array<resource_id>", "Optional non-empty array of registered, non-air item IDs; prefer explicit namespaces. Omission means no type filter. Null, [], non-string entries, air, unknown IDs and tags are rejected; duplicates collapse. Applies together with drop_ref. It does not filter item components or express a desired count."),
                            field("radius", "integer", "Optional JSON number with an exact integer value, 1..48 inclusive, in blocks; default 16. Each scan expands the actor current body box on all axes. Null, 0, negatives, values above 48, fractional values, strings and booleans are rejected; 16.0 represents the same integer value as 16. This is neither a fixed work-site boundary nor a maximum follow distance for an already selected entity."),
                            field("may_alter_terrain", "boolean", "Optional JSON boolean; omission and false do not authorize route terrain changes. True permits pickup navigation to dig, bridge or pillar under clearance whitelist, protections and native conditions, including making body/headroom clearance for a mining drop. Null, numeric 0/1 and string booleans are rejected. It does not guarantee reachability or pickup; confirmed route changes remain in pickup_navigation.confirmed_terrain_changes.")));
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
                    "Physically explore from the current body position. Put fields in goal.parameters and omit goal.target. Choose at most one biome_id, biome_tag, structure_id or semantic_target; no selector means survey. Discover actual modded IDs with perceive(view=exploration,focus=biomes|biome_tags|structures,query=...). Direction restricts candidate places to a sector while routes may detour. coast means minecraft:beach. Survey records sampled observations, not exhaustive coverage; a failed search does not prove global absence. Read exploration_memory for saved/pending discoveries and query details on demand. Quality is judged by the model from observed facts; no hidden seed/locate is used. Repeated unreachable frontier legs can end with frontier_legs_circuit_broken.",
                    targets(), fields(
                            field("biome_id", "resource_id", "Exact registered biome; choose at most one target selector."),
                            field("biome_tag", "resource_id", "Registered biome tag, including mod tags."),
                            field("structure_id", "resource_id", "Structure evidence profile discovered through the exploration catalog."),
                            field("semantic_target", "string", "coast, biome id/#tag, or survey; no selector defaults to survey."),
                            field("direction", "string", "north, northeast, east, southeast, south, southwest, west, northwest, forward, backward, left or right. Relative heading is fixed at departure. Omit for all directions; up/down are not exploration sectors."),
                            field("angle_degrees", "integer", "Full sector width 1..360 degrees; default 90 with direction or 360 without. A 90-degree sector includes 45 degrees on either side. Without direction only 360 is accepted."),
                            field("min_distance", "integer", "Minimum candidate distance from the starting body position, in blocks; default 16 with direction or 0 otherwise. Zero permits nearby matches; must be nonnegative and no larger than max_distance."),
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
                    // 结构搜索从真实起点查线索；说明观察命中与到场复核的区别，避免把线索当作已抵达。
                    "Discover a world structure from the current body position through physical evidence. Put selectors and limits in goal.parameters. Strongholds require real ender-eye throws with allow_rare_consumables=true; other structures require a loaded-world evidence profile. Check the exploration structures catalog for observed mod support. reach_structure=true walks to and rechecks the evidence; false only discovers it. No match within the bound is not proof of global absence. target does not relocate the search origin; travel first to search from another region.",
                    targets("current_place", "area", "landmark", "prior_result"),
                    fields(
                            field("structure_id", "resource_id", "Required structure identity, such as minecraft:stronghold or minecraft:fortress."),
                            field("direction", "string", "Optional horizontal cardinal/diagonal or relative heading, fixed at start."),
                            field("angle_degrees", "integer", "Full sector width 1..360, default 90 with direction."),
                            field("min_distance", "integer", "Minimum target distance, default 16 with direction or 0 without."),
                            field("transport_mode", "auto|ground", "Native route preference, default auto."),
                            field("max_distance", "integer", "Physical search bound in blocks from the starting region; supported range 64..4096, default 4096. This bounds observed search, not world-wide structure existence."),
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
                    // 杀龙必须从当前已加载的活龙开始，晶体处理与最终死亡证据分别说明。
                    "Fight one currently loaded live vanilla Ender Dragon in minecraft:the_end. Put options in goal.parameters and explicitly set allow_combat=true; otherwise a decision is returned before combat. Observe towers, handle crystals/cages, attack, evade and recover, then confirm stable death/removal with death-phase or exit-portal evidence. A missing or unloaded dragon is not a victory. This ability does not enter the End, respawn a dragon, or guarantee modded boss mechanics; inspect phase, crystal counts, dragon_state and decision facts when it stops.",
                    targets("current_place", "area", "prior_result"),
                    fields(
                            field("allow_combat", "boolean", "Required explicit consent to destroy crystals and kill the dragon."),
                            field("may_alter_terrain", "boolean", "May open a freshly verified iron-bar crystal cage; default false."),
                            field("minimum_health", "number", "Finite health points, default 10 (=5 vanilla hearts), accepted 1..1024 and no greater than the body's current maximum health. Below this floor the encounter disengages and recovers; it is not a guaranteed remaining-health outcome."),
                            field("protected_labels", "array<string>", "Remembered places or possessions that must not be altered.")));
            case "maicraft:obtain_elytra" -> contract(
                    // 鞘翅以真实入包结算；折跃投珠、传送与末地船展示框各自保留确认事实。
                    "Obtain at least one real elytra in the main inventory, using goal.parameters. Already carrying one completes the goal; otherwise the body must be in minecraft:the_end. From the main island, acquire a pearl, approach an observed gateway, throw only with allow_rare_consumables=true, and verify same-dimension teleport; from an outer island search directly. Then find End City/ship evidence, release the elytra from its frame and collect it. Seeing a ship or breaking a frame is not inventory success. Inspect gateway_verified, ship_frame_verified, elytra_count, consumed, search_budget and recovery_options; an unconfirmed pearl throw is not blindly repeated.",
                    targets("current_place", "area", "prior_result"),
                    fields(
                            field("max_search_distance", "integer", "Physical outer-island End City search bound in blocks; supported range 128..4096, default 2048. Exhausting this bound reports remaining uncertainty rather than global absence."),
                            field("may_alter_terrain", "boolean", "Hard consent for route bridging, pillaring or clearing; default false."),
                            field("allow_combat", "boolean", "Default false. Enables handling loaded hostiles actively targeting the player and blocking progress; also enables the hunting source when acquiring a missing gateway pearl. It does not guarantee a safe fight or available supplies."),
                            field("allow_rare_consumables", "boolean", "Explicitly permits the real ender-pearl use required for End Gateway traversal; default false."),
                            field("protected_labels", "array<string>", "Remembered areas or possessions that must not be touched.")));
            case "maicraft:craft" -> contract(
                    // 先解释最终库存、真实工作面与逐批原生确认，再交代旧参数转换和未接通目标位置，避免目录承诺超出执行器。
                    "用途：让当前主背包及快捷栏中的指定物品最终达到 count；已携带数量计入目标，整批配方可使结果略多。参数放在 goal.parameters，材料倾向不是 goal.preferences。"
                            + " 入口复用取物任务，固定获取来源 inventory、craft；可原生取出支持的 Sophisticated Backpacks 随身包现货，并递归合成中间件。不会因缺料自动取世界容器或 AE 现货、挖矿、烧炼、采收、交易或狩猎；这些应另用 acquire_items 并明确来源。容量受阻后的整理可能把不需物品存入随身包或可用随身 AE，此存入分支与获取许可分开。"
                            + " 过程：先核对最终库存，再选普通配方和工作面；2×2 配方用背包网格，较大配方使用已放置的可用台、摆出随身台，或在原来源内先补一张台。需要真实已加载摆放位置与原生交互，不把背包中的台当作远程 3×3 网格。已持有一张台不追加造第二张；中间件到包后，在已提交子动作结清后收起旧材料分支。"
                            + " 配方书可用时原生摆料，否则用原生材料谓词和逐格点击；逐批等待准确产物、组件、数量和实际入包，归还自有网格材料并关闭菜单，再尝试回收登记的临时台。不会清走外来光标或网格物品；回收失败可保留合成成功与未回收事实。机器加工、特殊配方及未识别的模组桌面不保证支持。"
                            + " plan 接受不代表材料够或工作面可用；execute 返回 accepted 只说明登记。跟随 next_attention，看最终 state、goal_satisfied、observed_final_count、missing，以及 attempts[].child_data 的 recipe、crafted、completed_batches、crafting_placement_method、crafting_grid_cleanup_verified 和三项工作台放置/回收字段。recipe_trace 的材料账只用于规划，feasible 不证明原料已在世界中可得。"
                            + " 失败先读具体 failure_code、failure_detail、issues 和未知效果；NO_SPACE 也可能是光标/网格占用，不能只据父层容量摘要重复调用。按当前 decision_id 和列出的 choice 答复，retry 的参数放 details.parameters，前置或换目标用 details.goal。暂停不回滚原生操作，取消保留既有物品和世界变化；重启/换世界后未完成语义任务先暂停，不复用旧菜单或临时台所有权。"
                            + " 目标位置边界：目录虽保留 nearest、prior_result，但适配器不按它们定位异地工作台；请省略 target，异地操作先 travel。缺 item_id 时只存在 target.label 作为物品编号的兼容回退。数量原始值未在计划阶段严格校验，具体宽松转换见 count。"
                            + " 完整 plan 工具参数示例：{\"goal\":{\"ability\":\"maicraft:craft\",\"outcome\":\"让主背包火把达到8支\",\"parameters\":{\"item_id\":\"minecraft:torch\",\"count\":8}}}",
                    targets("nearest", "prior_result"),
                    fields(
                            field("item_id", "resource_id", "goal.parameters.item_id：正常调用必填的产物物品 ID，推荐 namespace:id；执行时须为已注册非空气物品。省略/null 沿兼容路径尝试 target.label，仍无编号则要求补充；不接受本能力的物品数组或标签字段。"),
                            field("preferred_materials", "array<resource_id>", "goal.parameters.preferred_materials：可选软偏好，必须是带命名空间的真实物品 ID，去重后最多256种；省略/null/[] 为无偏好，数组中的空白/null 不合法。可影响中间配方；现货覆盖优先，偏好不可用时保留其他路线，不扩大获取来源。"),
                            field("count", "integer", "goal.parameters.count：推荐整数1..256，默认1，单位为最终主背包数量，不是新增数量或点击次数。当前适配器对省略/null/对象/数组采用默认1，对原始标量先 getAsInt 再夹到1..256；因此0变1，小数可能截断、数字字符串可能被接收，非法转换可能到执行时才失败。不要依赖这些宽松行为；acquire_items 的数量检查更严格。")));
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
                    // 调用者声明最终库存与来源许可；角色先查现货再递归准备，实际入包、未知事务和恢复选择分别说明。
                    "用途：让主背包和快捷栏内可接受物品的合计数最终达到 count；装备、副手、鼠标、合成格、随身包内部及远端存储数量都不是已到手。item_id/item_ids/item_tag/item_tags 至少提供一种，可同时提供并求并集；它们是互换候选，不是要逐项采购的清单，多种各要若干请拆成独立目标。"
                            + " 参数全部位于 goal.parameters，source_hint 的子字段位于 goal.parameters.source_hint，preferred_materials 不在 goal.preferences。省略 target 或仅用 {\"kind\":\"nearest\"}；nearest 不接受 label、position、relation，去别处取材应先用 sequence 中的 travel。未知参数键直接拒绝，ID和标签成员在游戏中再次核对。"
                            + " 过程：每刻先看最终库存与未结原生子事务，再撤销已被现货满足的旧材料需求；许可 inventory/storage 时先从支持的随身背包取货，随后按许可调查可见普通容器、观察无线现货，并在剩余来源中按现场条件选择。容器记忆只提供复访线索，真实菜单和入包确认后才更新结果；网络不可读不等于网络无货。每次只有一项身体子任务，前置工具、燃料和工作台继承原来源。"
                            + " craft 先用现成材料与可准备工作面，再比较完整材料账；材料偏好及已观察附近野生来源帮助排序，不承诺全图最优，也不保证总追到最底层。cook 可保留已允许的多段前置烹饪并用祖先链防循环；harvest 需结清补种。mine 只从公平可见/记忆来源开始，可用 FTB Ultimine 按原生预览处理受约束的连接矿脉；没有该模组仍走原生逐块路径。探矿须另开 allow_prospecting 且已允许 mine。"
                            + " 容器范围从请求原点冻结，省略 radius 时最多32格且需直接视线；递归、排队和恢复不重新扩大这个范围。普通源扫描只用已加载事实；狩猎前沿搜索会真实移动，并只约束每次子搜索相对其启动位置的水平距离，不是整个请求的固定围栏。hint 是线索并与内置知识合并，不是放宽权限或强制替换内置来源。"
                            + " 完成以实际主包数量为准，同时结清已提交的子事务；看 state、required_final_count、observed_final_count、missing、goal_satisfied、attempts、recipe_trace、issues、effects_observed 和 outcome_uncertain。preparation_plan 的 feasible/search_complete/estimated_cost 表达只读材料账，不代表原生动作成功；产物显示、击杀或计划可行都不能代替入包。"
                            + " 容量故障可能在保留任务原料后把其他物品存入随身背包或可用随身 AE，单调用最多四轮；当前存入分支不按 allowed_sources 过滤，具体转移见 inventory_maintenance。未确认转移、未结炉次、缺失保护范围或已产生效果后关键前置失败应保留真实回执，不自动用另一条路线掩盖。普通来源耗尽可返回 planning_handoff/知识链接，不自动造机器或把 EMI 展示当成已生产。"
                            + " plan 只登记计划，execute 的 accepted 不是完成；跟随 next_attention。失败按当前 decision_id 与列出的 choice 回答，retry 可给 details.parameters，recover/replace_goal 用 details.goal；恢复建议不会自行执行。pause/resume 管理现有任务，有待答问题先 answer；cancel 不回滚。死亡或换世界/重启恢复后未完成任务先暂停，重新核对库存与现场，未知原容器范围不得拿当前位置补造。"
                            + " 完整 plan 工具参数示例：{\"goal\":{\"ability\":\"maicraft:acquire_items\",\"outcome\":\"让主背包可替代木板合计达到16张\",\"parameters\":{\"item_tag\":\"minecraft:planks\",\"count\":16,\"preferred_materials\":[\"minecraft:birch_log\"]}}}",
                    targets("nearest"),
                    fields(
                            field("item_id", "resource_id", "goal.parameters.item_id：单个可接受的已注册非空气物品；与 item_ids/item_tag/item_tags 至少一种，四种可合并。推荐 namespace:id；显式null或空白拒绝。合并展开去重后最多256个物品ID。"),
                            field("item_ids", "array<resource_id>", "goal.parameters.item_ids：可互换物品ID数组，主包计数合计，不是有顺序的配方或逐项采购单。省略/null/[] 为空；元素须为非空字符串，执行时验证注册物品，去重后与标签成员共享256种上限。"),
                            field("item_tag", "resource_id", "goal.parameters.item_tag：一个实时物品标签，推荐 namespace:id，可带#；省略不提供标签，显式null/空白拒绝。执行时展开当前成员；未知或空标签拒绝，不表示没有缺料。"),
                            field("item_tags", "array<resource_id>", "goal.parameters.item_tags：多个实时物品标签的并集，可带#。省略/null/[] 为空；元素非空，未知或空标签在执行时拒绝，展开成员与其他选择共享256种上限。"),
                            field("preferred_materials", "array<resource_id>", "goal.parameters.preferred_materials：带命名空间的真实非空气物品ID数组，去重后最多256种；省略/null/[] 无偏好。软偏好沿配方中间件共享，现货覆盖优先，不是 recipe_id 或材料禁用列表；不追加采矿、仓库等许可。"),
                            field("count", "integer", "goal.parameters.count：最终主背包可接受物品的合计数量，整数1..2304，省略/null 为1；0、小数、字符串和越界值拒绝。已带物品算在其中，若要新增应在当前总数上增加目标。整批输出可能超出目标；数量上限不保证实际堆叠容量。"),
                            field("allowed_sources", "array<string>", "goal.parameters.allowed_sources：获取来源硬限制，不是动作顺序。省略/null/[] 使用 inventory,storage,nearby,wireless,harvest,craft,cook,mine,hunt；trade 须显式加入。可选枚举就是这十项，元素去空白、忽略大小写。inventory 主包计数始终做；inventory/storage 还允许支持的随身背包取货。nearby 收掉落；storage 调查普通可见容器并可取支持的AE现货；wireless 仅随身无线现货，不开普通容器、不委托网络合成；craft 普通格子配方；cook 炉类加工；harvest 成熟作物及补种；mine 合格源方块；trade 原生交易；hunt 核实关系后狩猎且还需allow_harm。默认不开交易、不默认许可伤害。容量维护的存入见总说明，不属于本字段过滤的取货路径。"),
                            field("allow_harm", "boolean", "goal.parameters.allow_harm：是否允许狩猎来源伤害已核实目标；省略/null/false 均不允许，只有JSON true 开启，字符串/数字拒绝。仍须允许hunt，并有合并后的实体类型和与当前需求相交的expected_item_ids；保护和实体关系继续检查。"),
                            field("may_alter_terrain", "boolean", "goal.parameters.may_alter_terrain：mine 接近来源时可挖阶梯、通道或垫高；省略/null/true 开启，false 限普通行走接近，字符串/数字拒绝。不改变目标源筛选、天然树资格或保护范围，也不是禁止挖目标方块的总开关；探矿有单独的显式授权。"),
                            field("allow_prospecting", "boolean", "goal.parameters.allow_prospecting：省略/null/false 关闭；true 且已允许mine时，公平扫描空手后按已知生成带开下降通道，再水平掘进并收取暴露的矿源。层位、方向、预算由执行器处理，无原生连锁能力时逐块清通；未知生成带如实停止，不把授权当成已发现矿。字符串/数字拒绝。"),
                            field("protected_labels", "array<string>", "goal.parameters.protected_labels：来自已记忆地点的明确保护名称，去重后最多64项；省略/null/[] 无新增标签，元素非空字符串。无法对应当前世界时停止，不把失效名称当成无保护；不要在这里传坐标或自行编造引用。"),
                            field("radius", "integer", "goal.parameters.radius：整数1..48格，0/小数/字符串/越界拒绝。显式整数约束附近来源与采矿，容器冻结范围最多32格。省略时采矿用有效已加载视距，其他附近来源默认16格，容器32格。当前显式null的数值默认16，采矿仍视为未指定半径，但容器变成16格；此差异尚未统一。"),
                            field("max_distance", "integer", "goal.parameters.max_distance：仅狩猎前沿搜索使用，整数16..2048格，省略/null 为512，0和非整数拒绝；其他来源不使用。按每次GenericEntitySearch子任务启动位置计算水平距离，不是整项取物的固定起点范围；降低它可缩短单次搜索，但不保证不会因后续重新搜索而更远。"),
                            field("source_hint", "object", "goal.parameters.source_hint：省略或{}为空，显式null和未知子键拒绝。只接受block_ids:string[]（方块ID）与block_tags:string[]（标签可带#），两者合并去重最多64项；entity_type_ids:string[]（已注册实体类型，最多32种）；expected_item_ids:string[]（已注册非空气预期物品，最多64种）；trade_profession_ids:string[]（可解析职业ID，最多32种，目前仅保存/合并，未接入reviewTrade筛选，不能当作职业限制）；description:非空string（可省略，显式null拒绝）。数组省略/null/[] 为空，但元素不能是null/空白。顶层提示与内置来源求并集，不覆盖内置知识、不逐层复制给递归材料；不得含坐标、运行时实体UUID、路线、槽位或点击。狩猎要求合并后有实体类型且expected_item_ids与当前需求相交；例如{\"entity_type_ids\":[\"minecraft:cod\"],\"expected_item_ids\":[\"minecraft:cod\"]}。")));
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
            // 区域任务会调查、移动、供料和复测；只承诺所选样本比例，参数裁剪与取材扩展等现有限制也要公开。
            case "maicraft:light_area" -> contract(
                    "Light an observed area as a normal body task. Put options in goal.parameters and the location in sibling goal.target; leave preferences and hard constraints empty. Requires current_place, same-dimension coordinates with position:{x,y,z,dimension?}, a remembered area/landmark label, or an unambiguous successful prior_result from this task (label/relation may disambiguate). An unknown named place never falls back to your feet. area targets always discover a connected component; other targets do so when radius is absent. A supplied radius bounds either mode. Connected discovery may travel to load frontiers; explicit-circle scanning stops on unloaded columns. Walkable connectivity may extend outside a named base, and sampling is ground-based, not every voxel or every floor. Observe -> choose source/candidates -> supply current batch if needed -> re-observe -> place -> settle -> measure BLOCK light -> corrective pass. Default all/8 means every selected walkable feet sample; crop_growth defaults to 9. Default source is a torch; survival consuming torches use the offhand fast pass, other sources and free-material mode use Build. Source preferences are not strict material locks. Only native receipts confirm placements; theoretical spacing does not prove illumination. Read verified, required_coverage/achieved_coverage, lighting_observation (dark positions, minimum light; -1 means unavailable/no samples), build_receipts and supply_receipts. most/player_visibility success may retain dark cells. accepted is registration, not completion. Budget exhaustion, unavailable terrain/materials or no improving candidates preserve partial changes and return failure/decision evidence; use the actual task/next_attention response to continue. Pause retains in-memory work; restart recreates the unfinished step and observes again. Current parent pause does not explicitly forward to every nested lighting child, and cancellation cleanup does not merge the active build pass receipt into the parent: interrupted effects need fresh inspection. Use auto_light to assist an existing task without replacing it. Plan request when the player explicitly chose a six-block radius: {\"goal\":{\"ability\":\"maicraft:light_area\",\"outcome\":\"照亮当前位置水平半径六格内的目标地面\",\"target\":{\"kind\":\"current_place\"},\"parameters\":{\"radius\":6,\"minimum_light\":8,\"coverage\":\"all\",\"block_id\":\"minecraft:torch\",\"material_policy\":\"inventory_only\"}}}. Crop example, with the player already beside the intended farmland: {\"goal\":{\"ability\":\"maicraft:light_area\",\"outcome\":\"给身边连通农田补光，优先中央未种植处\",\"target\":{\"kind\":\"current_place\"},\"parameters\":{\"coverage\":\"crop_growth\",\"placement_preference\":\"central_unplanted\",\"material_policy\":\"inventory_only\"}}}. plan does not execute; execute the returned plan_id to act.",
                    targets("area", "landmark", "coordinates", "current_place", "prior_result"),
                    fields(
                            field("radius", "integer", "Optional horizontal circle radius in blocks, declared range 1..29999984. Omitted/null discovers a matching connected component from the seed; do not invent a smaller boundary. area + radius discovers the connected subset within that circle. Current public adapter clamps out-of-range/zero and can coerce fractional or string input before strict internal parsing; supply a valid integer rather than relying on rejection. It is not a vertical-volume boundary."),
                            field("minimum_light", "integer", "BLOCK-light threshold 1..15; omitted/null defaults to 9 for crop_growth, otherwise 8. Independent of sky light. Current public adapter clamps/coerces before strict internal parsing, so 0 is not a disable switch. Higher thresholds may have no feasible supported layout; failure must not silently lower the requested threshold."),
                            field("coverage", "string", "all (omitted/null default) requires 100% of sampled walkable feet cells; most requires 90%, player_visibility 95% of the same samples, with no extra eye-level or rendered-visibility test. crop_growth requires 100% of crop/farmland-above samples. Exact lowercase enum; empty string is invalid. Current public adapter treats an object/array value as omitted and uses all; submit a valid string. Only all/crop_growth require every selected sample, and none guarantees every spawning rule or removal of existing mobs."),
                            field("block_id", "resource_id", "Optional first light-source preference, e.g. minecraft:torch, before light_preferences; omitted/null/blank adds none. Not an exact material constraint: unknown/non-emitting/unusable preferences can be skipped in favor of carried or standard alternatives. The returned light_source identifies what was selected."),
                            field("light_preferences", "array<resource_id>", "Ordered nonblank light-source IDs after block_id; omitted/null/[] adds none. Default is torch when no preferences exist. Carried alternatives and standard fallbacks remain eligible; this is not an allowed-material whitelist."),
                            field("material_policy", "string", "ordinary (omitted/null/blank default), storage_available or inventory_only. ordinary with no allowed_sources tries inventory, storage/crafting, nearby drops, harvest, cook and mine. inventory_only stays in inventory unless allowed_sources explicitly contains storage; that conflict currently opens storage and craft. Use inventory_only with no allowed_sources for carried-only work. Governs supply, not a strict light-source palette."),
                            field("allowed_sources", "array<string>", "inventory, nearby, wireless, storage, harvest, craft, cook, mine, trade or hunt; nonblank strings. Omitted/null/[] uses material_policy defaults. Shared supply always includes inventory, and enabling storage also enables craft; an explicit storage entry overrides inventory_only. This current behavior is not a strict source whitelist."),
                            field("allow_harm", "boolean", "Explicit harmful acquisition permission passed to supply. Omitted/null/false denies it; only true grants it. Numeric or string booleans are invalid. Does not describe lighting coverage."),
                            field("protected_labels", "array<string>", "Remembered same-dimension nonblank labels; omitted/[] adds none, null is invalid. Area placement protects each anchor within +/-4 blocks on each axis plus observed sensitive cells. Earlier measured footprints can also apply through sequence protection. A name alone does not guarantee protection of a whole arbitrarily shaped base; unresolved or cross-dimension labels stop before placement."),
                            field("style", "string", "auto (omitted/null default), ground or unobtrusive; normalized case/whitespace, empty string invalid at the public adapter. All use supported ground candidates. unobtrusive prefers farther-from-center ties under coverage_optimal and excludes the unplanted-farmland exception. Wall and hanging area planners are unsupported."),
                            field("max_placements", "integer", "Optional whole-task placement decision budget, 1..24000. Omitted/null means no explicit cap; 0, fractions, string numerals, booleans and out-of-range values are rejected. Counts submitted torch sites or dispatched ordinary-build sites, not confirmed consumption. Reaching it preserves already placed lights and reports unfinished coverage."),
                            field("placement_preference", "string", "coverage_optimal (omitted default), central_unplanted or unobtrusive; exact lowercase string, null invalid. Tie-break only after estimated coverage gain over measured dark cells. central_unplanted requires crop_growth and favors available unplanted farmland near the seed; unobtrusive favors greater distance. Does not guarantee a particular cell or that every planner candidate passes the fast torch path's stricter native support checks.")));
            // 随行配置即时完成，灯光效果另由逐刻原生动作与路线采样证明；省略 action 与会话初始关闭是两种语义。
            case AutomaticLightingAdapter.ABILITY -> contract(
                    "Configure or inspect automatic route lighting. All fields belong in goal.parameters; omit target and leave preferences/hard constraints empty. Disabled initially and after connection reset; a bare explicit auto_light request defaults to enable. Standalone execute completes synchronously without replacing/pausing the current task or taking manual controls. A sequence containing this step is still a normal body task. Configuration success is not torch-equipment or coverage success: later eligible ticks move a carried stack to the offhand using a native inventory swap, returning the old offhand item to that backpack slot. While automation owns controls, primary actions, navigation and rescue run first. No detours, acquisition, terrain breaking or separate navigation. Grounded, idle body and a visible/reachable supported AIR cell are required; swimming, lava, item use, menus and precise primary aim yield. Check BLOCK light at visited feet/eyes, submit one offhand action, confirm world/inventory/server evidence, wait for propagation, and do not submit again within two blocks of the last submission origin. Missing torches or inaccessible supports leave reported gaps. Disable stops new actions and keeps reading submitted placement receipts; it does not remove lights or restore the old offhand. Status re-reads automatic_lighting: enabled/state, torch_inventory actual total/backpack/off_hand counts, minimum_observed_block_light (null if none), below_target, unloaded_cells, placements and coverage_verified. Coverage applies only to this body's sampled route, never the whole cave; dark/unloaded cells prevent verification. Configuration labels protect landmark anchor cells, not complete remembered-area geometry. A player-object change clears route/action history but retains settings; connection cleanup resets to off. For a fresh status read use a new request key; an identical retry reuses the prior result. Plan request to enable: {\"goal\":{\"ability\":\"maicraft:auto_light\",\"outcome\":\"沿当前路线按需补光\",\"parameters\":{\"action\":\"enable\",\"minimum_light\":8}}}. Read-only status: {\"goal\":{\"ability\":\"maicraft:auto_light\",\"outcome\":\"检查随行补光状态\",\"parameters\":{\"action\":\"status\"}}}. Stop new actions: {\"goal\":{\"ability\":\"maicraft:auto_light\",\"outcome\":\"关闭随行补光\",\"parameters\":{\"action\":\"disable\"}}}. Planning alone changes nothing; execute the returned plan_id or the goal to apply.",
                    targets(), fields(
                            field("action", "string", "enable (default only when explicitly invoking auto_light), disable or status. Exact lowercase string; null/blank/false/0 invalid. enable and disable rebuild minimum_light and configured labels from this request, so omitted settings reset to defaults rather than retaining previous ones. status validates fields but does not apply them. The independent configuration record can complete while the current main task continues."),
                            field("minimum_light", "integer", "Integer BLOCK light 1..13, default 8 when omitted; null/0/fractions/string numerals/booleans invalid. Independent of daylight. enable/disable applies this request's value; status does not change the saved threshold. Torches emit 14, but no detours means high targets and obstructed routes can remain dark. Use light_area for systematic repair."),
                            field("protected_labels", "array<string>", "Nonblank remembered labels; omitted/[] adds none, null invalid. Merged with inherited sequence labels and, during ticks, the current task's labels. Current resolver protects anchor cells in this dimension, not full historical region footprints. An unresolved label yields unresolved_protection at runtime, even after configuration success; foreign-dimension anchors are skipped.")));
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
