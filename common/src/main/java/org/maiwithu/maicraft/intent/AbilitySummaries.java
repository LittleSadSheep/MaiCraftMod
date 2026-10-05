package org.maiwithu.maicraft.intent;

/** 模型先从发现列表选择玩家目标，再读取完整用法；这里按业务用途写摘要，不按字数裁掉调用事实。 */
final class AbilitySummaries {
    private AbilitySummaries() {}

    static String describe(String ability) {
        return switch (ability) {
            // 先选行动与控制方式；接单、持续跟随、等待满足和真正动作完成各有自己的终止语义。
            case "maicraft:sequence" -> "Execute semantic goals in order with an explicit failure policy and individual step outcomes.";
            case "maicraft:chat" -> "Type and submit one native chat message or command; client submission and server effects are separate facts.";
            case "maicraft:wait_for_condition" -> "Wait for a minimum amount of game time, then for the selected world or body condition.";
            case "maicraft:follow" -> "Continuously follow one selected loaded entity until stopped or failed; reaching the following distance does not finish the task.";
            case "maicraft:combat" -> "Fight selected loaded targets or defend against current threats with explicit harm permission; use actual defeat and loot evidence to judge completion.";
            case "maicraft:suicide" -> "Explicitly seek in-game death through observed native hazards or a self-lit fire from carried flint and steel under keepInventory conditions; death and later respawn are confirmed separately.";

            // 跑图靠实际移动和观察；已知地点的到达精度、现场发现与持久记忆登记不能相互冒充。
            case "maicraft:travel" -> "Reach a located destination with configurable horizontal and height tolerances or exact grounded standing; also supports declared discovery and transport modes.";
            case "maicraft:explore" -> "Physically survey terrain or discover a biome, tag or structure in an optional direction sector, recording observed places.";
            case "maicraft:find_structure" -> "Find a world structure through first-person evidence and optionally travel to recheck the observed location.";
            case "maicraft:find_block" -> "Scan visible block or pool evidence in currently loaded terrain without moving; report the actual observation scope and matches.";
            case "maicraft:find_entity" -> "Search for the requested currently visible entities by registered type, relation and optional traits.";
            case "maicraft:remember_place" -> "Register or overwrite a named world location in runtime memory; the later persistent save is a separate step.";

            // 取材和加工都要说明数量终点；主包目标、单次采收、扫取与切制次数不是同一个计数口径。
            case "maicraft:acquire_items" -> "Reach a requested final main-inventory quantity through selected acquisition sources and recipe prerequisites.";
            case "maicraft:craft" -> "Reach a final inventory quantity through inventory or crafting-table recipes and permitted grid-craftable intermediates.";
            case "maicraft:harvest_block" -> "Harvest one specified observed block and settle its native drops, keeping source destruction and received output separate.";
            case "maicraft:collect_items" -> "Collect one observed drop reference or sweep nearby loose items, confirming pickup through the evidence available to that mode.";
            case "maicraft:cook" -> "Use a nearby furnace-type device to obtain the final inventory quantity, preparing ingredients and fuel and settling the current batch.";
            case "maicraft:trade" -> "Use native merchant offers to reach a final main-inventory quantity under the selected payment policy.";
            case "maicraft:stonecut" -> "Process carried input through a loaded stonecutter for the requested recipe uses and report observed inventory changes.";

            // 原生交互的完成效果由回执解释；丢弃可能主动开挖或点火，开箱成功也须另读实际菜单状态。
            case "maicraft:interact" -> "Approach and perform a native block or entity interaction; inspect the branch-specific evidence for its actual effects.";
            case "maicraft:use_item" -> "Use a carried item at a specified location or along the current view, with supported batches counted by received output.";
            case "maicraft:use_container" -> "Approach and right-click an observed container; read the receipt to determine whether its menu actually opened.";
            case "maicraft:manage_container" -> "Deposit, withdraw or balance items through native container transfers using the declared quantity goal.";
            case "maicraft:drop_items" -> "Dispose of a stated item quantity; the current executor may move, dig a side pocket, ignite drops and recover obstructing leftovers.";
            case "maicraft:consume" -> "Eat one carried food item and report observed native consumption and body changes.";
            case "maicraft:equip" -> "Hold, equip or remove selected equipment; inspect actual slots and still-worn items to determine the effect.";
            case "maicraft:fish" -> "Perform the requested number of fishing harvest cycles and retrieve their observed loot.";
            case "maicraft:sleep" -> "Approach or place a bed and confirm lying down; the public task does not wait until morning.";

            // 建筑设计、配置开关与身体施工分别描述；结构观察和生产验证仍按各自原生证据结算。
            case "maicraft:design_build" -> "Author, save, inspect, revise or preview a declarative building model without starting construction.";
            case "maicraft:build" -> "Construct an authored model at its fixed site or resume a frozen project, retaining native effects and declared-target differences.";
            case "maicraft:auto_light" -> "Enable, disable or query torch assistance along the current task; configuration success is not a coverage result.";
            case "maicraft:light_area" -> "Place light sources and remeasure actual block light over the selected walkable or farmland samples.";
            case "maicraft:inspect_machine" -> "Read current machine layout, declared-target differences and native observations without claiming production success.";
            case "maicraft:design_machine" -> "Review a supplied machine declaration and observed interfaces before native assembly.";
            case "maicraft:build_machine" -> "Assemble an authored machine blueprint and report native effects and whole-machine differences; production is checked separately.";
            case "maicraft:modify_machine" -> "Apply a declared modification to a recorded machine and compare the updated whole-machine requirements.";
            case "maicraft:operate_machine" -> "Perform a selected native machine operation or production process and report its actual effects and verification evidence.";
            case "maicraft:connect_mechanical_power" -> "Connect observed Create kinetic endpoints through native transmission construction and inspect the resulting connection.";
            case "maicraft:enchant" -> "Compatibility entry for a bounded native enchanting operation; new process requests use the machine operation contract.";

            // 阶段目标仍要求当前现场与真实产物；任务书按钮提交不等于服务器发奖，见到展示框不等于鞘翅入包。
            case "maicraft:defeat_ender_dragon" -> "Fight the currently loaded Ender Dragon encounter and corroborate actual death or removal.";
            case "maicraft:obtain_elytra" -> "Bring a real elytra into the main inventory through observed gateway, End City, ship and pickup steps.";
            case "maicraft:quest_action" -> "Submit, confirm or claim one selected native FTB Quests action; request delivery, quest state and inventory effects are distinct.";
            // 起飞前受力、真实组装、部件输入和持续飞控各有独立证据；模型先选用途，再读完整用法。
            case "maicraft:physical_balance" -> "起飞前比较受力、启停和扰动并推荐配重；只有明确 apply 才原生施工，预测与实机验证分开。";
            case "maicraft:physical_assembly" -> "用强力胶或蜂蜜胶粘接，并通过物理组装器创建或拆回结构，保留原生转换和整机声明差异。";
            case "maicraft:physical_control" -> "配置物理部件与无线打字机，执行有限输入并观察反馈；原生输入、实际设置和载具运动分别确认。";
            case "maicraft:fly_vehicle" -> "登记操纵映射，或原生登机执行持续飞控；起飞、巡航、局部避障、着陆与松键停稳分别确认。";

            // 本轮未复盘的能力沿用其维护会话的原摘要，不替仍在运行的功能工作改写契约。
            default -> null;
        };
    }
}
