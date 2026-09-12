package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import org.maiwithu.maicraft.core.pathing.util.ClientSurfaceHeight;
import org.maiwithu.maicraft.core.pathing.transport.TransportMode;
import org.maiwithu.maicraft.core.data.WorldTimeSemantics;
import org.maiwithu.maicraft.core.scan.TargetIndex;

import java.util.List;
import java.util.UUID;

/**
 * 把模型填好的目标翻译成下一步该做什么，例如“去营地”变成调用移动工具。
 * 这里按 ability 和参数字段分支，不直接理解用户随口说的一句话。
 * 返回的也不一定是动作：可能要继续找信息，或者先问调用者怎么决定。
 */
final class AbilityAdapter {

    private AbilityAdapter() {}

    static IntentAction adapt(Goal goal, LocalPlayer player, IntentRuntime runtime) {
        return adapt(goal, player, runtime, null);
    }

    static IntentAction adapt(
            Goal goal, LocalPlayer player, IntentRuntime runtime, UUID continuationToken) {
        // 机器能力交给机器适配器，战斗、交互等交给通用适配器，其余在下面逐项处理。
        if (MachineAbilityAdapter.supports(goal.ability())) {
            return MachineAbilityAdapter.adapt(goal, player, runtime, continuationToken);
        }
        if (GeneralAbilityAdapter.supports(goal.ability())) {
            return GeneralAbilityAdapter.adapt(goal, player, runtime);
        }
        return switch (goal.ability()) {
            case ChatAbilityAdapter.ABILITY -> ChatAbilityAdapter.adapt(goal);
            case "maicraft:remember_place" -> remember(goal, player, runtime);
            case "maicraft:sleep" -> sleep(goal, player);
            case "maicraft:travel" -> travel(goal, player, runtime);
            case "maicraft:travel_dimension" -> travelDimension(goal);
            case "maicraft:find_structure" -> findStructure(goal);
            case "maicraft:reach_milestone" -> reachMilestone(goal);
            case "maicraft:defeat_ender_dragon" -> defeatEnderDragon(goal);
            case "maicraft:obtain_elytra" -> obtainElytra(goal);
            case "maicraft:craft" -> craft(goal);
            case "maicraft:cook" -> cook(goal);
            case "maicraft:trade" -> trade(goal);
            case "maicraft:build" -> build(goal, player, runtime);
            case BuildDesignAdapter.ABILITY -> BuildDesignAdapter.design(goal, player, runtime);
            case "maicraft:light_area" -> lightArea(goal, player, runtime);
            case "maicraft:connect_mechanical_power" ->
                    connectPower(goal, player, runtime, continuationToken);
            case "maicraft:acquire_items" -> acquire(goal);
            case "maicraft:wait_for_condition" -> waitFor(goal, player);
            default -> decision(goal,
                    "No semantic adapter is registered for " + goal.ability() + ". Choose explicitly.",
                    List.of(
                            option("replace_goal", "Provide details.goal as one supported semantic Goal."),
                            option("skip", "Skip this step and continue the sequence."),
                            option("cancel", "Cancel the whole task.")));
        };
    }

    static IntentAction fromAnswer(Goal goal, IntentTaskRecord.DecisionAnswer answer,
                                   LocalPlayer player, IntentRuntime runtime) {
        return fromAnswer(goal, answer, player, runtime, null, null);
    }

    static IntentAction fromAnswer(
            Goal goal, IntentTaskRecord.DecisionAnswer answer,
            LocalPlayer player, IntentRuntime runtime, UUID continuationToken,
            JsonObject priorFailure) {
        // 重试前先看上次结果是否允许重复；例如机器可能已经被部分修改，不能不明情况再做一遍。
        if ("retry".equals(answer.choice())
                && !RecoveryAdvisor.ordinaryRetryAllowed(priorFailure)) {
            return new IntentAction.Decision(
                    RecoveryAdvisor.retryRefused(goal, priorFailure));
        }
        JsonObject details = answer.details();
        JsonObject updates = details.has("parameters") && details.get("parameters").isJsonObject()
                ? details.getAsJsonObject("parameters")
                : details;
        JsonObject merged = goal.parameters();
        // 把答复里新给的参数覆盖到本次执行使用的目标上，再重新选择动作。
        merge(merged, updates);
        return adapt(goal.withParameters(merged), player, runtime, continuationToken);
    }

    private static IntentAction remember(Goal goal, LocalPlayer player, IntentRuntime runtime) {
        // 地点必须有一个以后能引用的短名字；普通地点与需要保护的聚居地由 area_role 明确区分。
        JsonObject parameters = goal.parameters();
        String label = string(parameters, "label");
        if (label == null && goal.target() != null) label = goal.target().label();
        if (label == null || label.isBlank()) {
            // 例如记成“西岸营地”，以后才能说去这个名字的地方；缺名字时不拿整句任务描述凑数。
            return decision(goal,
                    "remember_place needs a short durable label (parameter \"label\"),"
                            + " e.g. \"western shore camp\".",
                    List.of(option("replace_goal",
                                    "Provide details.goal with parameters.label set to a short name."),
                            option("cancel", "Cancel the task.")));
        }
        String areaRoleId = string(parameters, "area_role");
        IntentRuntime.LandmarkAreaRole areaRole;
        if (areaRoleId == null || "ordinary".equals(areaRoleId)) {
            areaRole = IntentRuntime.LandmarkAreaRole.ORDINARY;
        } else if ("managed_settlement".equals(areaRoleId)) {
            areaRole = IntentRuntime.LandmarkAreaRole.MANAGED_SETTLEMENT;
        } else {
            return decision(goal,
                    "remember_place area_role must be ordinary or managed_settlement; no protection role was inferred from the label.",
                    List.of(option("replace_goal",
                                    "Provide details.goal with a declared area_role."),
                            option("cancel", "Cancel the task.")));
        }
        Goal.WorldPosition position = rememberPosition(goal, player, runtime);
        if (position == null) {
            return decision(goal,
                    "remember_place needs current_place, coordinates, or an existing landmark target.",
                    List.of(option("skip", "Do not create a landmark."),
                            option("cancel", "Cancel the task.")));
        }
        return new IntentAction.Remember(label, position, areaRole);
    }

    private static IntentAction sleep(Goal goal, LocalPlayer player) {
        // 先判断这里能否安全用床，避免为爆炸维度安排找床、放床或等待夜晚。
        if (!BedBlock.canSetSpawn(player.level())) {
            return decision(goal, "Beds explode in this dimension; sleeping here is unsafe.",
                    List.of(option("recover", "Provide a travel prerequisite to a dimension where beds work."),
                            option("skip", "Continue without sleeping."),
                            option("cancel", "Cancel the whole task.")));
        }
        // 先找已加载区域里的床；查询分多刻进行，没有查完就等，不把“暂时没找到”当作“没有”。
        java.util.Set<Block> bedBlocks = BuiltInRegistries.BLOCK
                .getTag(BlockTags.BEDS)
                .map(tag -> tag.stream().map(holder -> holder.value())
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()))
                .orElseGet(java.util.Set::of);
        if (!bedBlocks.isEmpty()) {
            TargetIndex.register(player.clientLevel, bedBlocks);
            TargetIndex.Result beds;
            try {
                beds = TargetIndex.query(
                        player.clientLevel,
                        player.blockPosition(),
                        bedBlocks,
                        1,
                        2,
                        256);
            } finally {
                TargetIndex.unregister(player.clientLevel, bedBlocks);
            }
            if (!beds.complete()) return IntentAction.Pending.INSTANCE;
            if (!beds.hits().isEmpty()) {
                if (!WorldTimeSemantics.canAttemptSleep(player.level())) {
                    return waitForNightDecision(goal);
                }
                Block bed = player.clientLevel.getBlockState(beds.hits().getFirst()).getBlock();
                String bedId = BuiltInRegistries.BLOCK.getKey(bed).toString();
                // 找到床后安排两步：先走到这种床旁边，再调用“上床”工具。
                JsonObject travel = new JsonObject();
                travel.addProperty("block", bedId);
                return new IntentAction.Chain(List.of(
                        new IntentAction.Tool("goto", travel.toString()),
                        new IntentAction.Tool("sleep", "{}")));
            }
        }

        String carriedBed = inventoryBed(player);
        // 世界里没有找到床，再看背包有没有；有床则找位置放下，之后仍按“走过去、上床”执行。
        if (carriedBed != null) {
            if (!WorldTimeSemantics.canAttemptSleep(player.level())) {
                return waitForNightDecision(goal);
            }
            BedSite site = nearbyBedSite(player);
            if (site != null) {
                JsonObject op = new JsonObject();
                op.addProperty("op", "set");
                op.addProperty("block_id", carriedBed);
                op.addProperty("x", site.foot().getX());
                op.addProperty("y", site.foot().getY());
                op.addProperty("z", site.foot().getZ());
                op.addProperty("facing", site.facing().getName());
                JsonArray ops = new JsonArray();
                ops.add(op);
                JsonObject build = new JsonObject();
                build.add("ops", ops);
                build.addProperty("replace_existing", false);
                JsonObject travel = new JsonObject();
                travel.addProperty("block", carriedBed);
                return new IntentAction.Chain(List.of(
                        new IntentAction.Tool("build", build.toString()),
                        new IntentAction.Tool("goto", travel.toString()),
                        new IntentAction.Tool("sleep", "{}")));
            }
            return decision(goal,
                    "There is a bed in inventory, but no safe loaded two-block placement site nearby.",
                    List.of(
                            option("recover", "Provide a semantic travel prerequisite to reach a safe open area."),
                            option("skip", "Do not sleep."),
                            option("cancel", "Cancel the whole task.")));
        }

        return decision(goal,
                "No bed is visible in loaded terrain and no bed is in inventory. "
                        + "Choose a semantic prerequisite before MaiCraft changes the world.",
                List.of(
                        option("recover", "Provide details.goal, for example acquiring any usable bed."),
                        option("skip", "Continue without sleeping."),
                        option("cancel", "Cancel the whole task.")));
    }

    private static IntentAction waitForNightDecision(Goal goal) {
        // 目前的处理是询问要不要先等到夜里，并不会在睡觉任务里自动等，也不会反复点床。
        return decision(goal,
                "A usable bed is available, but the observed world is currently daytime and not thundering. MaiCraft will not click it repeatedly or pretend sleep succeeded.",
                List.of(
                        option("recover", "Provide details.goal to wait for the observable night condition, then resume sleep."),
                        option("skip", "Continue without sleeping."),
                        option("cancel", "Cancel the whole task.")));
    }

    private static String inventoryBed(LocalPlayer player) {
        // 按物品栏顺序找第一件对应床方块的物品；这里的名称用于后面的放置和找床。
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem item
                    && item.getBlock() instanceof BedBlock) {
                return BuiltInRegistries.BLOCK.getKey(item.getBlock()).toString();
            }
        }
        return null;
    }

    private static BedSite nearbyBedSite(LocalPlayer player) {
        // 从附近一圈圈找能放床的两格，床头可以朝四个水平方向。
        // 这里每列只看最高的非树叶地形上方，没围绕玩家当前高度找室内或洞穴地板。
        BlockPos origin = player.blockPosition();
        for (int radius = 1; radius <= 5; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    int x = origin.getX() + dx;
                    int z = origin.getZ() + dz;
                    int y = ClientSurfaceHeight.motionBlockingNoLeaves(
                            player.clientLevel, x, z);
                    BlockPos foot = new BlockPos(x, y, z);
                    for (Direction facing : Direction.Plane.HORIZONTAL) {
                        BlockPos head = foot.relative(facing);
                        if (validBedCell(player, foot) && validBedCell(player, head)) {
                            return new BedSite(foot, facing);
                        }
                    }
                }
            }
        }
        return null;
    }

    private static boolean validBedCell(LocalPlayer player, BlockPos cell) {
        // 床要占的位置必须能被替换，上方不挡人，下面要能托住床；床头和床尾分别检查。
        if (!player.clientLevel.isLoaded(cell)
                || !player.clientLevel.getBlockState(cell).canBeReplaced()
                || !player.clientLevel.getBlockState(cell.above()).getCollisionShape(
                        player.clientLevel, cell.above()).isEmpty()) {
            return false;
        }
        BlockPos support = cell.below();
        return player.clientLevel.isLoaded(support)
                && player.clientLevel.getBlockState(support)
                        .isFaceSturdy(player.clientLevel, support, Direction.UP);
    }

    private record BedSite(BlockPos foot, Direction facing) {}

    private static IntentAction travel(Goal goal, LocalPlayer player, IntentRuntime runtime) {
        // 先分清用户想去哪：指定电梯楼层、某艘船、一个方向的平台、方块、坐标，或还没找到的地区。
        if(ElevatorTravelIntent.applies(goal)) return ElevatorTravelIntent.adapt(goal,player);
        JsonObject parameters = new JsonObject();
        if (goal.parameters().has("structure_id")) {
            // 这里的 structure_id 是已观察到的船体编号；当前登船入口只走喷气背包方案。
            if (goal.target() != null || goal.parameters().has("destination") || goal.parameters().has("semantic_target")
                    || goal.parameters().has("biome_id") || goal.parameters().has("biome_tag"))
                throw new IllegalArgumentException("structure_id names one physical vessel; do not combine it with another destination");
            TransportMode transport = TransportMode.parse(string(goal.parameters(), "transport_mode"));
            if (transport != TransportMode.AUTO && transport != TransportMode.JETPACK)
                throw new IllegalArgumentException("physical boarding uses transport_mode=auto or jetpack");
            parameters.addProperty("structure_id", java.util.UUID.fromString(string(goal.parameters(), "structure_id")).toString());
            return new IntentAction.Tool("board_structure",parameters.toString());
        }
        TravelDestination.validatePrecision(goal.parameters());
        TravelDestination destination = TravelDestination.fromGoal(goal);
        TransportMode mode = TransportMode.parse(string(goal.parameters(), "transport_mode"));
        String discovery=exploreTarget(goal);
        // “往下找个平台”还没有坐标；这里只记方向和搜索范围，边移动边观察时再确定落脚处。
        if ("platform".equals(discovery)) {
            if (destination!=null || goal.target()!=null && !"nearest".equals(goal.target().kind())
                    || goal.parameters().has("block_id") || goal.parameters().has("block")
                    || goal.parameters().has("biome_id") || goal.parameters().has("biome_tag"))
                throw new IllegalArgumentException("platform discovery cannot be combined with another destination");
            if (bool(goal.parameters(),"exact",false) || mode==TransportMode.ELEVATOR)
                throw new IllegalArgumentException("platform discovery uses a region and auto, ground or jetpack transport");
            String direction=string(goal.parameters(),"direction");
            if (direction==null) direction="forward";
            org.maiwithu.maicraft.core.pathing.goal.RegionalGoal.direction(direction,0);
            parameters.addProperty("direction",direction);
            parameters.addProperty("transport_mode",mode.name().toLowerCase(java.util.Locale.ROOT));
            parameters.addProperty("max_distance",integer(goal.parameters(),"max_distance",64,8,128));
            parameters.addProperty("may_alter_terrain",bool(goal.parameters(),"may_alter_terrain",false)
                    || bool(goal.preferences(),"may_alter_terrain",false));
            return new IntentAction.Tool("travel_region",parameters.toString());
        }
        if (goal.parameters().has("direction")) throw new IllegalArgumentException("direction is for platform discovery");
        parameters.addProperty("transport_mode", mode.name().toLowerCase(java.util.Locale.ROOT));
        String block = string(goal.parameters(), "block_id");
        if (block == null) block = string(goal.parameters(), "block");
        if (block != null) {
            // 找某种方块是走到它旁边能操作的位置，不是让玩家站进那个方块，因此不接受 exact=true。
            if (bool(goal.parameters(), "exact", false))
                throw new IllegalArgumentException("exact=true needs located x/y/z coordinates; a block search only selects an approach stance");
            parameters.addProperty("block", block);
        } else if (destination != null) {
            // 已给出目的地时，先确认在当前维度；别的维度必须先完成跨维度旅行。
            if (destination.dimension() != null
                    && !destination.dimension().equals(player.level().dimension().location().toString())) {
                return decision(goal, "Travel destination is in another dimension; reach that dimension first.",
                        List.of(option("recover", "Use maicraft:travel_dimension to reach the destination dimension."),
                                option("cancel", "Cancel travel.")));
            }
            destination.addCoordinates(parameters);
        } else {
            Goal.WorldPosition position = position(goal, player, runtime);
            if (position == null) {
                Goal.SemanticTarget semantic = goal.target();
                if (isNamedPlace(semantic)) {
                    // “营地”没记住时先询问，不能拿玩家现在的位置代替，也不能把名字猜成生物群系。
                    return unresolvedNamedPlaceDecision(
                            goal, semantic, player, runtime, "Travel");
                }
                if (semantic != null && "prior_result".equals(semantic.kind())) {
                    return decision(goal,
                            "Travel could not bind prior_result to one authoritative earlier successful place. "
                                    + "MaiCraft refused to guess a destination or start an unrelated exploration.",
                            List.of(
                                    option("recover", "Provide details.goal to produce or remember the intended place first."),
                                    option("replace_goal", "Use a remembered landmark, current_place, or an explicit coast/biome discovery goal."),
                                    option("cancel", "Cancel without moving.")));
                }
                String exploreTarget = exploreTarget(goal);
                if (bool(goal.parameters(), "exact", false)) {
                    return decision(goal, "Exact travel needs a located destination with a known height first; "
                                    + "discovery reaches a matching region without guessing one exact cell.",
                            List.of(option("replace_goal", "Use exact=false for region discovery or provide a located destination."),
                                    option("cancel", "Cancel travel.")));
                }
                if (mode == TransportMode.JETPACK || mode == TransportMode.ELEVATOR) {
                    // 海岸／群系探索在这里被限定为 ground 或 auto；指定飞行／电梯必须先有已定位的终点。
                    return decision(goal, "Jetpack or elevator travel needs a located destination first; "
                                    + "an undiscovered coast or biome cannot supply a verified transport endpoint.",
                            List.of(option("replace_goal", "Choose coordinates or a remembered destination, "
                                            + "or use transport_mode=ground/auto to discover it first."),
                                    option("cancel", "Cancel travel.")));
                }
                if (bool(goal.parameters(), "allow_water_bucket_fall", false)
                        || bool(goal.parameters(), "allow_landing_assists", false)) {
                    return decision(goal, "Landing-assisted travel needs an observed destination first.",
                            List.of(option("replace_goal", "Choose coordinates or a remembered destination."),
                                    option("cancel", "Cancel travel.")));
                }
                if (exploreTarget == null) {
                    return decision(goal,
                            "Travel needs coordinates, a remembered landmark, block_id, coast, biome id or biome tag.",
                            List.of(option("replace_goal", "Provide a semantic destination, never waypoints."),
                                    option("cancel", "Cancel the task.")));
                }
                JsonObject explore = new JsonObject();
                explore.addProperty("target", exploreTarget);
                explore.addProperty("transport_mode", mode.name().toLowerCase(java.util.Locale.ROOT));
                explore.addProperty("max_distance",
                        integer(goal.parameters(), "max_distance", 768, 64, 2_048));
                if (bool(goal.parameters(), "may_alter_terrain", false)
                        || bool(goal.preferences(), "may_alter_terrain", false)) {
                    explore.addProperty("may_alter_terrain", true);
                }
                return new IntentAction.Tool("explore", explore.toString());
            }
            parameters.addProperty("x", position.x());
            parameters.addProperty("y", position.y());
            parameters.addProperty("z", position.z());
        }
        if (parameters.has("x")) {
            // 只有已经定位的坐标移动才把到达误差交给移动工具；找区域有自己的一套完成判断。
            parameters.addProperty("exact", bool(goal.parameters(), "exact", false));
            for (String precision : List.of("horizontal_radius", "vertical_tolerance")) {
                if (goal.parameters().has(precision))
                    parameters.add(precision, goal.parameters().get(precision).deepCopy());
            }
        }
        if (bool(goal.parameters(), "may_alter_terrain", false)
                || bool(goal.preferences(), "may_alter_terrain", false)) {
            parameters.addProperty("may_alter_terrain", true);
        }
        if (bool(goal.parameters(), "allow_water_bucket_fall", false))
            parameters.addProperty("allow_water_bucket_fall", true);
        if (bool(goal.parameters(), "allow_landing_assists", false))
            parameters.addProperty("allow_landing_assists", true);
        return new IntentAction.Tool("goto", parameters.toString());
    }

    private static IntentAction travelDimension(Goal goal) {
        // 只告诉底层要去哪一维度、最多找多远；传送门的位置和实际穿门步骤由跨维度任务负责。
        JsonObject parameters = goal.parameters();
        String destination = string(parameters, "destination_dimension");
        Goal.SemanticTarget target = goal.target();
        if (destination == null && target != null && target.position() != null) {
            destination = target.position().dimension();
        }
        if (destination == null && target != null && target.label() != null
                && ResourceLocation.tryParse(target.label()) != null) {
            destination = target.label();
        }
        if (destination == null || ResourceLocation.tryParse(destination) == null) {
            return decision(goal,
                    "Cross-dimension travel needs a namespaced destination_dimension; portal cells and the route remain MaiCraft's responsibility.",
                    List.of(
                            option("retry", "Retry with details.parameters.destination_dimension."),
                            option("skip", "Do not change dimensions."),
                            option("cancel", "Cancel the task.")));
        }
        JsonObject args = new JsonObject();
        args.addProperty("destination_dimension", destination);
        args.addProperty("max_search_radius",
                integer(parameters, "max_search_radius", 128, 16, 512));
        if (bool(parameters, "may_alter_terrain", false)
                || bool(goal.preferences(), "may_alter_terrain", false)) {
            args.addProperty("may_alter_terrain", true);
        }
        for (String key : List.of("prepare_portal", "allow_rare_consumables", "allow_combat",
                "max_search_distance", "allowed_sources", "material_policy", "protected_labels")) {
            if (parameters.has(key)) args.add(key, parameters.get(key).deepCopy());
        }
        return new IntentAction.Tool("dimension_travel", args.toString());
    }

    private static IntentAction findStructure(Goal goal) {
        // 这里的结构是村庄、要塞等世界建筑类型；与 travel 里用于登船的 structure_id 含义不同。
        JsonObject parameters = goal.parameters();
        String structure = string(parameters, "structure_id");
        Goal.SemanticTarget target = goal.target();
        if (structure == null && target != null && target.label() != null
                && ResourceLocation.tryParse(target.label()) != null) {
            structure = target.label();
        }
        if (structure == null || ResourceLocation.tryParse(structure) == null) {
            return decision(goal,
                    "Physical structure discovery needs a namespaced structure_id; MaiCraft chooses every search segment and observation.",
                    List.of(
                            option("retry", "Retry with details.parameters.structure_id."),
                            option("skip", "Do not search for a structure."),
                            option("cancel", "Cancel the task.")));
        }
        JsonObject args = new JsonObject();
        args.addProperty("structure_id", structure);
        args.addProperty("max_distance",
                integer(parameters, "max_distance", 4_096, 64, 4_096));
        args.addProperty("reach_structure",
                !parameters.has("reach_structure")
                        || bool(parameters, "reach_structure", true));
        if (bool(parameters, "may_alter_terrain", false)
                || bool(goal.preferences(), "may_alter_terrain", false)) {
            args.addProperty("may_alter_terrain", true);
        }
        if (bool(parameters, "allow_rare_consumables", false)) {
            args.addProperty("allow_rare_consumables", true);
        }
        return new IntentAction.Tool("structure_search", args.toString());
    }

    private static IntentAction defeatEnderDragon(Goal goal) {
        // 当前要求显式填写 allow_combat；参数没给时先询问，不把能力名本身当作这个布尔开关。
        JsonObject parameters = goal.parameters();
        if (!bool(parameters, "allow_combat", false)) {
            return decision(goal,
                    "Defeating the Ender Dragon destroys crystals and kills a living boss. Confirm that combat is intended before MaiCraft acts.",
                    List.of(
                            option("retry", "Retry with details.parameters.allow_combat=true if this fight is intended."),
                            option("skip", "Leave the dragon encounter untouched."),
                            option("cancel", "Cancel the task.")));
        }
        JsonObject args = new JsonObject();
        args.addProperty("allow_combat", true);
        if (bool(parameters, "may_alter_terrain", false)
                || bool(goal.preferences(), "may_alter_terrain", false)) {
            args.addProperty("may_alter_terrain", true);
        }
        for (String key : List.of("minimum_health", "protected_labels")) {
            if (parameters.has(key)) args.add(key, parameters.get(key).deepCopy());
        }
        return new IntentAction.Tool("dragon_fight", args.toString());
    }

    private static IntentAction reachMilestone(Goal goal) {
        // 只接受列出的四个进度目标；接下来要进下界、找要塞还是打龙，由里程碑任务拆步骤。
        JsonObject parameters = goal.parameters();
        String milestone = string(parameters, "milestone");
        Goal.SemanticTarget target = goal.target();
        if (milestone == null && target != null) milestone = target.label();
        if (!List.of("nether", "stronghold", "defeat_dragon", "elytra")
                .contains(milestone)) {
            return decision(goal,
                    "reach_milestone needs milestone=nether, stronghold, defeat_dragon or elytra. MaiCraft derives the private prerequisite chain.",
                    List.of(
                            option("retry", "Retry with details.parameters.milestone."),
                            option("cancel", "Cancel progression.")));
        }
        JsonObject args = new JsonObject();
        args.addProperty("milestone", milestone);
        args.addProperty("max_search_distance",
                integer(parameters, "max_search_distance", 4_096, 128, 4_096));
        args.addProperty("max_portal_search_radius",
                integer(parameters, "max_portal_search_radius", 128, 16, 512));
        if (bool(parameters, "may_alter_terrain", false)
                || bool(goal.preferences(), "may_alter_terrain", false)) {
            args.addProperty("may_alter_terrain", true);
        }
        for (String key : List.of(
                "minimum_health", "allow_combat", "allow_rare_consumables",
                "allowed_sources", "material_policy", "protected_labels", "prepare_portal")) {
            if (parameters.has(key)) args.add(key, parameters.get(key).deepCopy());
        }
        return new IntentAction.Tool("reach_milestone", args.toString());
    }

    private static IntentAction obtainElytra(Goal goal) {
        // 把搜索范围及战斗、珍贵消耗品等允许项交给找鞘翅任务；这里不决定路线或攻击目标。
        JsonObject parameters = goal.parameters();
        JsonObject args = new JsonObject();
        args.addProperty("max_search_distance",
                integer(parameters, "max_search_distance", 2_048, 128, 4_096));
        if (bool(parameters, "may_alter_terrain", false)
                || bool(goal.preferences(), "may_alter_terrain", false)) {
            args.addProperty("may_alter_terrain", true);
        }
        if (bool(parameters, "allow_combat", false)) {
            args.addProperty("allow_combat", true);
        }
        if (bool(parameters, "allow_rare_consumables", false)) {
            args.addProperty("allow_rare_consumables", true);
        }
        if (parameters.has("protected_labels")) {
            args.add("protected_labels", parameters.get("protected_labels").deepCopy());
        }
        return new IntentAction.Tool("obtain_elytra", args.toString());
    }

    private static String exploreTarget(Goal goal) {
        // 按优先顺序取探索类型、群系 ID、群系标签，最后才读 nearest 目标里的名字或关系描述。
        String target = string(goal.parameters(), "semantic_target");
        if (target == null) target = string(goal.parameters(), "biome_id");
        if (target == null) {
            String tag = string(goal.parameters(), "biome_tag");
            if (tag != null) target = tag.startsWith("#") ? tag : "#" + tag;
        }
        Goal.SemanticTarget semantic = goal.target();
        if (target == null && semantic != null && "nearest".equals(semantic.kind())) {
            target = semantic.label();
        }
        if (target == null && semantic != null && "nearest".equals(semantic.kind())) {
            target = semantic.relation();
        }
        return target == null || target.isBlank() ? null : target.strip();
    }

    private static IntentAction craft(Goal goal) {
        // 对外的 craft 实际调用“凑齐物品”：只允许背包现有物品和合成，不自动挖矿或向容器取货。
        // count 是背包最终要有多少，已达到数量就无需再合成；中间配方由取物任务继续拆解。
        JsonObject parameters = goal.parameters();
        String item = itemId(goal, parameters);
        if (item == null) {
            return decision(goal, "Craft needs a namespaced item_id.",
                    List.of(option("retry", "Retry with details.parameters.item_id and count."),
                            option("cancel", "Cancel the task.")));
        }
        JsonObject args = new JsonObject();
        args.addProperty("item_id", item);
        args.addProperty("count", integer(parameters, "count", 1, 1, 256));
        JsonArray sources = new JsonArray();
        sources.add("inventory");
        sources.add("craft");
        args.add("allowed_sources", sources);
        return new IntentAction.Tool("acquire_items", args.toString());
    }

    private static IntentAction cook(Goal goal) {
        // 烹饪要的成品、总数量、允许的燃料和原料来源传给烹饪任务；炉子菜单怎么操作留给它处理。
        JsonObject parameters = goal.parameters();
        String item = itemId(goal, parameters);
        if (item == null) {
            return decision(goal, "Cook needs a namespaced item_id.",
                    List.of(option("retry",
                                    "Retry with details.parameters.item_id and count."),
                            option("cancel", "Cancel the task.")));
        }
        JsonObject args = new JsonObject();
        args.addProperty("item_id", item);
        args.addProperty("count", integer(parameters, "count", 1, 1, 256));
        for (String key : List.of(
                "recipe_preference", "allowed_fuels", "allowed_sources",
                "allow_harm", "protected_labels")) {
            if (parameters.has(key)) {
                args.add(key, parameters.get(key).deepCopy());
            }
        }
        return new IntentAction.Tool("cook", args.toString());
    }

    private static IntentAction trade(Goal goal) {
        // 只指定想换到的物品和愿意支付的物品等条件；具体商人、交易项和点击顺序由交易任务选择。
        JsonObject parameters = goal.parameters();
        String item = itemId(goal, parameters);
        if (item == null) {
            return decision(goal, "Trade needs a namespaced item_id for the desired output.",
                    List.of(
                            option("retry", "Retry with details.parameters.item_id and count."),
                            option("cancel", "Cancel the trade.")));
        }
        JsonObject args = new JsonObject();
        args.addProperty("item_id", item);
        args.addProperty("count", integer(parameters, "count", 1, 1, 256));
        for (String key : List.of(
                "merchant_kind", "allowed_payment_items", "protected_labels", "radius")) {
            if (parameters.has(key)) args.add(key, parameters.get(key).deepCopy());
        }
        return new IntentAction.Tool("trade_items", args.toString());
    }

    private static IntentAction build(Goal goal, LocalPlayer player, IntentRuntime runtime) {
        if (goal.parameters().has("project_id")) return BuildProjectAdapter.plan(goal, player, runtime);
        if (BuildingSceneContract.supports(goal)) return BuildingSceneAdapter.adapt(goal, player, runtime);
        // 房屋尺寸、地形与材料会影响实际方案，把这些交给专门的建造规划器处理。
        return SemanticBuildPlanner.plan(goal, player, runtime);
    }

    private static IntentAction lightArea(Goal goal, LocalPlayer player, IntentRuntime runtime) {
        // 先确定照亮哪个地方；找不到点名的地点时先询问，不能改成照亮玩家脚下。
        JsonObject parameters = goal.parameters();
        Goal.WorldPosition center = position(goal, player, runtime);
        Goal.SemanticTarget target = goal.target();
        boolean semanticPlace = target != null
                && ("area".equals(target.kind()) || "landmark".equals(target.kind()));
        if (center == null && semanticPlace) {
            return unresolvedNamedPlaceDecision(
                    goal, target, player, runtime, "Lighting");
        }
        if (center == null && target != null && "prior_result".equals(target.kind())) {
            return decision(goal,
                    "Lighting prior_result did not match one authoritative earlier successful "
                            + "place. MaiCraft refused to illuminate the player's current area instead.",
                    List.of(
                            option("recover", "Provide details.goal to produce or remember the intended area first."),
                            option("replace_goal", "Use current_place or an exact label from perceive(view=landmarks)."),
                            option("cancel", "Cancel without changing any area.")));
        }
        if (center == null) {
            return decision(goal,
                    "Lighting needs current_place, same-dimension coordinates, a resolved landmark, or an earlier verified result.",
                    List.of(option("replace_goal", "Provide one unambiguous semantic target."),
                            option("cancel", "Cancel the task.")));
        }
        boolean explicitRadius = parameters.has("radius")
                && !parameters.get("radius").isJsonNull();
        boolean resolveLoadedComponent = (target != null && "area".equals(target.kind()))
                || !explicitRadius;
        // 指定 area 或未给半径时，交给照明任务从附近已加载方块判断区域边界。
        String coverage = string(parameters, "coverage");
        if (coverage == null) coverage = "most";
        if (!List.of("all", "most", "crop_growth", "player_visibility").contains(coverage)) {
            return decision(goal,
                    "Unsupported lighting coverage: " + coverage,
                    List.of(option("replace_goal", "Choose all, most, crop_growth or player_visibility."),
                            option("cancel", "Cancel the task.")));
        }
        if (parameters.has("placement_preference")
                && (!parameters.get("placement_preference").isJsonPrimitive()
                        || !parameters.getAsJsonPrimitive("placement_preference").isString())) {
            return decision(goal,
                    "Lighting placement_preference must be a string enum value.",
                    List.of(option("replace_goal", "Choose coverage_optimal, central_unplanted or unobtrusive."),
                            option("cancel", "Cancel the task.")));
        }
        String placementPreference = string(parameters, "placement_preference");
        if (placementPreference == null) placementPreference = "coverage_optimal";
        if (!List.of("coverage_optimal", "central_unplanted", "unobtrusive")
                .contains(placementPreference)) {
            return decision(goal,
                    "Unsupported lighting placement_preference: " + placementPreference,
                    List.of(option("replace_goal", "Choose coverage_optimal, central_unplanted or unobtrusive."),
                            option("cancel", "Cancel the task.")));
        }
        if ("central_unplanted".equals(placementPreference)
                && !"crop_growth".equals(coverage)) {
            // 当前把“放在中央未种植处”限定为农作物照明，其他照明目的在这里拒绝这一偏好。
            return decision(goal,
                    "central_unplanted placement_preference is only valid for crop_growth coverage.",
                    List.of(option("replace_goal", "Use crop_growth coverage or choose another placement preference."),
                            option("cancel", "Cancel the task.")));
        }
        String style = string(parameters, "style");
        if (style != null) style = style.strip().toLowerCase(java.util.Locale.ROOT);
        if (style != null && !List.of("auto", "ground", "unobtrusive").contains(style)) {
            return decision(goal,
                    "Unsupported lighting style: " + style,
                    List.of(option("replace_goal", "Choose auto, ground or unobtrusive."),
                            option("cancel", "Cancel the task.")));
        }
        JsonObject args = new JsonObject();
        args.addProperty("center_x", center.x());
        args.addProperty("center_y", center.y());
        args.addProperty("center_z", center.z());
        if (explicitRadius) {
            args.addProperty("radius", integer(parameters, "radius", 1, 1,
                    org.maiwithu.maicraft.core.task.lighting.SemanticLightAreaTaskRecord.MAX_EXPLICIT_RADIUS));
        }
        args.addProperty("coverage", coverage);
        args.addProperty("placement_preference", placementPreference);
        args.addProperty("minimum_light", integer(parameters, "minimum_light",
                "crop_growth".equals(coverage) ? 9 : 8, 1, 15));
        args.addProperty("resolve_loaded_component", resolveLoadedComponent);
        if (target != null && target.label() != null && !target.label().isBlank()) {
            args.addProperty("semantic_target", target.label());
        }
        for (String key : List.of("style", "block_id", "light_preferences",
                "material_policy", "allowed_sources", "allow_harm",
                "protected_labels", "max_placements")) {
            if (parameters.has(key)) args.add(key, parameters.get(key).deepCopy());
        }
        if (!args.has("protected_labels") && parameters.has("preserve")
                && parameters.get("preserve").isJsonArray()) {
            args.add("protected_labels", parameters.get("preserve").deepCopy());
        }
        return new IntentAction.Tool("light_area", args.toString());
    }

    private static IntentAction connectPower(
            Goal goal, LocalPlayer player, IntentRuntime runtime, UUID continuationToken) {
        // 动力源和接收端必须能对应到当前维度的已知位置，再交给机械连接任务查接口、布线和施工。
        JsonObject parameters = goal.parameters();
        String sourceLabel = string(parameters, "source_label");
        String destinationLabel = string(parameters, "target_label");
        Goal.WorldPosition source = namedPosition(sourceLabel, player, runtime);
        Goal.WorldPosition destination = position(goal, player, runtime);
        if (destination == null) destination = namedPosition(destinationLabel, player, runtime);
        if (source == null || destination == null) {
            String sourceIssue = source == null
                    ? namedEndpointIssue("source", sourceLabel, player, runtime)
                    : null;
            String destinationIssue = destination == null
                    ? destinationEndpointIssue(goal.target(), destinationLabel, player, runtime)
                    : null;
            String issue = sourceIssue == null ? destinationIssue
                    : destinationIssue == null ? sourceIssue
                    : sourceIssue + "; " + destinationIssue;
            return decision(goal,
                    "Mechanical connection paused before survey: " + issue
                            + ". MaiCraft refused to invent endpoint coordinates.",
                    List.of(option("recover", "Provide details.goal to reach and remember a verifiable endpoint; an arbitrary human label may need the player to identify it."),
                            option("replace_goal", "Use two same-dimension remembered labels or one authoritative prior_result destination."),
                            option("skip", "Leave the networks unchanged."),
                            option("cancel", "Cancel the whole task.")));
        }
        String requested = string(parameters, "transmission");
        String transmission;
        if (requested == null || "auto".equals(requested)) transmission = "auto";
        else if ("chain_drive".equals(requested) || "encased_chain_drive".equals(requested)) {
            transmission = "encased_chain_drive";
        } else {
            return decision(goal, "Unsupported mechanical transmission: " + requested,
                    List.of(option("replace_goal", "Choose automatic or chain_drive transmission."),
                            option("cancel", "Cancel the whole task.")));
        }

        JsonObject args = new JsonObject();
        args.addProperty("source_name", sourceLabel == null ? "source" : sourceLabel);
        args.addProperty("source_x", source.x());
        args.addProperty("source_y", source.y());
        args.addProperty("source_z", source.z());
        args.addProperty("destination_name", destinationLabel == null ? "destination" : destinationLabel);
        args.addProperty("destination_x", destination.x());
        args.addProperty("destination_y", destination.y());
        args.addProperty("destination_z", destination.z());
        args.addProperty("transmission", transmission);
        args.addProperty("allow_free_receiver", bool(parameters, "allow_new_receiver", false));
        if (continuationToken != null) {
            args.addProperty("continuation_token", continuationToken.toString());
        }
        for (String key : List.of(
                "material_policy", "allowed_sources", "allow_harm", "protected_labels")) {
            if (parameters.has(key)) args.add(key, parameters.get(key).deepCopy());
        }
        return new IntentAction.Tool("connect_mechanical_power", args.toString());
    }

    private static IntentAction acquire(Goal goal) {
        // 可以指定某件物品、一组可替代物品或物品标签；至少要有一种选择方式，否则不知道要取什么。
        JsonObject parameters = goal.parameters();
        boolean hasItem = parameters.has("item_id")
                && parameters.get("item_id").isJsonPrimitive()
                && !parameters.get("item_id").getAsString().isBlank();
        boolean hasAlternatives = parameters.has("item_ids")
                && parameters.get("item_ids").isJsonArray()
                && !parameters.getAsJsonArray("item_ids").isEmpty();
        boolean hasTag = parameters.has("item_tag")
                && parameters.get("item_tag").isJsonPrimitive()
                && !parameters.get("item_tag").getAsString().isBlank();
        boolean hasTags = parameters.has("item_tags")
                && parameters.get("item_tags").isJsonArray()
                && !parameters.getAsJsonArray("item_tags").isEmpty();
        if (!hasItem && !hasAlternatives && !hasTag && !hasTags) {
            return decision(goal, "Acquire items needs an item or semantic item tag selector.",
                    List.of(option("retry", "Retry with item_id/item_ids or item_tag/item_tags."),
                            option("cancel", "Cancel the task.")));
        }
        JsonObject args = new JsonObject();
        for (String key : List.of(
                "item_id", "item_ids", "item_tag", "item_tags", "count",
                "allowed_sources", "allow_harm",
                "protected_labels", "radius",
                "source_hint")) {
            if (parameters.has(key)) args.add(key, parameters.get(key).deepCopy());
        }
        return new IntentAction.Tool("acquire_items", args.toString());
    }

    private static IntentAction waitFor(Goal goal, LocalPlayer player) {
        // after_s 表示至少先等多久，不是最长等多久；到点后条件还没满足，仍会继续等。
        JsonObject parameters = goal.parameters();
        int seconds = integer(parameters, "after_s", 1, 0, 3600);
        String condition = string(parameters, "condition");
        if (condition == null) condition = "elapsed";
        if (!List.of("elapsed", "day", "night", "health_full", "not_hungry").contains(condition)) {
            return decision(goal, "Unsupported wait condition: " + condition,
                    List.of(option("skip", "Skip this wait."),
                            option("cancel", "Cancel the task.")));
        }
        return new IntentAction.Wait(condition, player.level().getGameTime() + seconds * 20L);
    }

    private static IntentAction.Decision decision(Goal goal, String question,
                                                   List<IntentTaskRecord.DecisionOption> options) {
        // 给这一个问题分配新编号，附上所属目标；后面的答复必须带回这个编号才能被接受。
        JsonObject context = new JsonObject();
        context.addProperty("ability", goal.ability());
        context.addProperty("outcome", goal.outcome());
        return new IntentAction.Decision(new IntentTaskRecord.DecisionSnapshot(
                UUID.randomUUID(), question, options, context.toString()));
    }

    private static IntentTaskRecord.DecisionOption option(String choice, String description) {
        return new IntentTaskRecord.DecisionOption(choice, description);
    }

    private static Goal.WorldPosition position(Goal goal, LocalPlayer player, IntentRuntime runtime) {
        // 真要在世界里做事时，只使用当前地点或当前维度的已知位置，不能拿别的维度的坐标直接行动。
        Goal.SemanticTarget target = goal.target();
        if (target == null) return null;
        if ("current_place".equals(target.kind())) return currentPosition(player);
        if ("coordinates".equals(target.kind())) return sameDimension(target.position(), player)
                ? target.position() : null;
        if ("landmark".equals(target.kind()) || "area".equals(target.kind())) {
            IntentRuntime.Landmark landmark = runtime.landmark(target.label());
            return landmark == null || !sameDimension(landmark.position(), player)
                    ? null : landmark.position();
        }
        return null;
    }

    private static Goal.WorldPosition namedPosition(
            String label, LocalPlayer player, IntentRuntime runtime) {
        // current_place 是专用值，表示玩家脚下；其他名字只从已经记住的地点表里找。
        if (label == null || label.isBlank()) return null;
        if ("current_place".equals(label)) return currentPosition(player);
        IntentRuntime.Landmark landmark = runtime.landmark(label);
        return landmark == null || !sameDimension(landmark.position(), player)
                ? null : landmark.position();
    }

    private static boolean isNamedPlace(Goal.SemanticTarget target) {
        return target != null
                && ("landmark".equals(target.kind()) || "area".equals(target.kind()));
    }

    private static IntentAction unresolvedNamedPlaceDecision(
            Goal goal, Goal.SemanticTarget target, LocalPlayer player,
            IntentRuntime runtime, String action) {
        // 分清“这个名字没记住”和“地方在另一个维度”，把原因说明白后让调用者选择下一步。
        String label = target.label() == null || target.label().isBlank()
                ? "the requested named place" : "'" + target.label() + "'";
        IntentRuntime.Landmark landmark = target.label() == null
                ? null : runtime.landmark(target.label());
        String reason;
        if (landmark == null) {
            reason = label + " is not remembered for this world";
        } else if (!sameDimension(landmark.position(), player)) {
            reason = label + " is remembered in another dimension";
        } else {
            reason = label + " could not be resolved from authoritative semantic memory";
        }
        return decision(goal,
                action + " target " + reason + ". MaiCraft refused to use the current position, "
                        + "guess coordinates, or reinterpret the label as an exploration target.",
                List.of(
                        option("recover", "Provide details.goal to reach and remember a verifiable place; an arbitrary ownership label may need the player to identify it."),
                        option("replace_goal", "Choose an exact label from perceive(view=landmarks), current_place, or an explicit coast/biome discovery."),
                        option("cancel", "Cancel without moving or changing the world.")));
    }

    private static String namedEndpointIssue(
            String endpoint, String label, LocalPlayer player, IntentRuntime runtime) {
        // 给机械连接的某一端生成具体错误说明：没填名字、没记住，或不在当前维度。
        if (label == null || label.isBlank()) return endpoint + "_label is missing";
        if ("current_place".equals(label)) return endpoint + " current_place is unavailable";
        IntentRuntime.Landmark landmark = runtime.landmark(label);
        if (landmark == null) return endpoint + " label '" + label + "' is not remembered";
        if (!sameDimension(landmark.position(), player)) {
            return endpoint + " label '" + label + "' belongs to another dimension";
        }
        return endpoint + " label '" + label + "' has no authoritative position";
    }

    private static String destinationEndpointIssue(
            Goal.SemanticTarget target, String label,
            LocalPlayer player, IntentRuntime runtime) {
        // 引用前一步结果失败，和普通地标名字没找到，是两类不同问题，分别报告。
        if (target != null && "prior_result".equals(target.kind())) {
            return "destination prior_result did not match one authoritative earlier successful place";
        }
        if (isNamedPlace(target)) {
            return namedEndpointIssue("destination", target.label(), player, runtime);
        }
        return namedEndpointIssue("destination", label, player, runtime);
    }

    private static Goal.WorldPosition rememberPosition(
        Goal goal, LocalPlayer player, IntentRuntime runtime) {
        // 记忆位置不要求玩家现在就在那个维度；和“马上前往／施工”的位置检查不同。
        Goal.SemanticTarget target = goal.target();
        if (target == null) return null;
        if ("current_place".equals(target.kind())) return currentPosition(player);
        if ("coordinates".equals(target.kind())) return target.position();
        if ("landmark".equals(target.kind()) || "area".equals(target.kind())) {
            IntentRuntime.Landmark landmark = runtime.landmark(target.label());
            return landmark == null ? null : landmark.position();
        }
        return null;
    }

    private static boolean sameDimension(Goal.WorldPosition position, LocalPlayer player) {
        return position != null && (position.dimension() == null
                || position.dimension().equals(player.level().dimension().location().toString()));
    }

    private static Goal.WorldPosition currentPosition(LocalPlayer player) {
        BlockPos pos = player.blockPosition();
        return new Goal.WorldPosition(pos.getX(), pos.getY(), pos.getZ(),
                player.level().dimension().location().toString());
    }

    private static String itemId(Goal goal, JsonObject parameters) {
        String item = string(parameters, "item_id");
        if (item == null && goal.target() != null) item = goal.target().label();
        return item;
    }

    private static JsonArray ids(Goal goal, JsonObject parameters) {
        for (String key : List.of("block_ids", "item_ids")) {
            if (parameters.has(key) && parameters.get(key).isJsonArray()) {
                return parameters.getAsJsonArray(key).deepCopy();
            }
        }
        JsonArray result = new JsonArray();
        String item = itemId(goal, parameters);
        if (item != null) result.add(item);
        return result;
    }

    private static String string(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()
                || !object.get(key).isJsonPrimitive()) return null;
        return object.get(key).getAsString();
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsBoolean()
                : fallback;
    }

    private static int integer(JsonObject object, String key, int fallback, int min, int max) {
        // 未填时用默认值，超出范围时直接压到边界值；本方法不会告诉调用者数值被裁剪。
        int value = object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsInt()
                : fallback;
        return Math.max(min, Math.min(max, value));
    }

    private static boolean numberInRange(JsonObject object, String key, int min, int max) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.getAsJsonPrimitive(key).isNumber()) return false;
        try {
            int value = object.get(key).getAsInt();
            return value >= min && value <= max;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static void merge(JsonObject target, JsonObject source) {
        // 按字段覆盖，并复制 JSON 内容，避免后面修改答复对象时连旧目标也一起变了。
        for (var entry : source.entrySet()) {
            target.add(entry.getKey(), entry.getValue().deepCopy());
        }
    }
}

sealed interface IntentAction {
    /** 信息还没查完，下一个游戏刻继续判断同一目标。 */
    enum Pending implements IntentAction { INSTANCE }
    /** 不用再做动作，直接交回结果；可附上 Mod 自己确认过的位置供后续步骤使用。 */
    record Report(org.maiwithu.maicraft.task.TaskResult result,
                  Goal.WorldPosition verifiedPosition) implements IntentAction {}
    /** 已经创建好具体任务单，交给总任务逐步执行。 */
    record Native(org.maiwithu.maicraft.task.TaskRecord record,boolean reobserveAfterSuccess) implements IntentAction {
        Native(org.maiwithu.maicraft.task.TaskRecord record) { this(record,false); }
    }
    /** 按顺序执行一组内部工具，例如先摆床、再走过去、最后躺下。 */
    record Chain(List<Tool> actions) implements IntentAction {
        public Chain {
            actions = List.copyOf(actions);
            if (actions.isEmpty()) throw new IllegalArgumentException("intent action chain cannot be empty");
        }
    }
    /** 调用一个内部工具；参数暂存为 JSON 文本，真正调用时再解析。 */
    record Tool(String toolName, String argumentsJson) implements IntentAction {
        JsonObject arguments() {
            return JsonParser.parseString(argumentsJson).getAsJsonObject();
        }
    }
    /** 只记下地点名字和位置，不要求玩家移动。 */
    record Remember(
            String label, Goal.WorldPosition position,
            IntentRuntime.LandmarkAreaRole areaRole) implements IntentAction {}
    /** 到指定游戏刻后开始查条件，满足才完成。 */
    record Wait(String condition, long notBeforeGameTime) implements IntentAction {}
    /** 当前无法自行继续，暂停并等待调用者回答这个问题。 */
    record Decision(IntentTaskRecord.DecisionSnapshot snapshot) implements IntentAction {}
}
