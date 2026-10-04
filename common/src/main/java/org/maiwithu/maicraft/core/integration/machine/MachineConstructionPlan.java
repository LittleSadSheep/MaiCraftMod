// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.function.BiPredicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.core.integration.machine.assembly.MachineInstallation;
import org.maiwithu.maicraft.core.integration.machine.layout.SemanticMachineLayout;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.task.build.ReplaceMode;
import org.maiwithu.maicraft.core.task.build.MachineSealingTaskRecord.Seal;
import com.google.gson.JsonArray;
import java.util.Comparator;
import net.minecraft.world.level.block.LiquidBlock;
import org.maiwithu.maicraft.core.integration.machine.utility.MachineUtilityInputs;
import org.maiwithu.maicraft.core.integration.create.CreateProcessingCapabilities;
import org.maiwithu.maicraft.core.integration.create.CreateBeltInstallation;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.core.integration.machine.assembly.MachineNativeInstallation;
import org.maiwithu.maicraft.core.blueprint.ConstructionOwnership;

/**
 * 把机器布局或逐格蓝图变成固定的装配计划：普通方块、AE2 部件、维护通道，以及最后要封闭的施工洞口。
 * 这里只决定要做什么，还没有观察每一格现场或安排角色动作。
 */
public final class MachineConstructionPlan {
    public record Part(BlockPos position, MachineInstallation.PartSpec spec) {}
    private final BlockPos anchor;
    private final String blueprintJson;
    private final List<BuildTaskRecord.Target> blocks;
    private final List<BuildTaskRecord.Target> fluidTargets;
    private final List<Part> parts;
    private final List<BlockPos> components;
    private final JsonObject report;
    private final boolean replace;
    private final boolean replaceBlockEntities;
    private final List<Seal> seals;
    private final List<MachineNativeInstallation> installations;
    private final List<MachineProcessingRelation> processing;
    private final Set<BlockPos> nativePositions;
    private final Map<BlockPos, BlockState> finalStates;
    private final Map<BlockPos, BuildTaskRecord.Target> targetsByPosition;
    private final Map<BlockPos, List<BlockPos>> placementDependencies;
    private final List<List<BuildTaskRecord.Target>> attachmentLayers;
    private Map<BlockPos, BlockState> observedEdits = Map.of();
    private Map<BlockPos, BlockState> ownedReplacements = Map.of();
    private boolean fixedModification;
    private boolean automaticModification;
    private boolean declaredModification;

    private MachineConstructionPlan(BlockPos anchor, List<BuildTaskRecord.Target> blocks,
            List<Part> parts, List<BlockPos> components, JsonObject report, boolean replace, boolean replaceBlockEntities,
            List<MachineNativeInstallation> installations, List<MachineProcessingRelation> processing, JsonObject blueprint) {
        // 原蓝图随实际锚点冻结，建成后和重新连接时都能按同一份目标读取地图差异。
        this.blueprintJson = blueprint.toString();
        this.anchor = anchor.immutable(); this.blocks = List.copyOf(blocks); this.parts = List.copyOf(parts);
        fluidTargets = blocks.stream().filter(MachineConstructionPlan::isFluid)
                .sorted(Comparator.comparingInt((BuildTaskRecord.Target target) -> target.pos().getY())
                        .thenComparingInt(target -> target.pos().getZ()).thenComparingInt(target -> target.pos().getX())).toList();
        this.components = List.copyOf(components); this.report = report.deepCopy(); this.replace = replace;
        this.replaceBlockEntities = replaceBlockEntities;
        this.installations = List.copyOf(installations); this.processing = List.copyOf(processing);
        Set<BlockPos> nativeCells = new LinkedHashSet<>(); installations.forEach(step -> nativeCells.addAll(step.targets().keySet()));
        nativePositions = Set.copyOf(nativeCells);
        Map<BlockPos, BuildTaskRecord.Target> byPosition = new LinkedHashMap<>();
        blocks.forEach(target -> byPosition.put(target.pos(), target));
        targetsByPosition = Map.copyOf(byPosition);
        Map<BlockPos, BlockState> finals = new LinkedHashMap<>(); blocks.forEach(target -> finals.put(target.pos(), target.desiredState()));
        installations.forEach(step -> finals.putAll(step.targets())); finalStates = Map.copyOf(finals);
        Map<BlockPos, List<BlockPos>> dependencies = new LinkedHashMap<>(); JsonArray dependencyReport = new JsonArray();
        for (var target : blocks) {
            // 传送带隧道与漏斗都排在原生皮带安装之后，不能因只登记了漏斗依赖而先对空气安装隧道。
            var required = MachinePlacementItems.supportDependencies(target.desiredState()).stream().map(target.pos()::offset).toList();
            if (required.isEmpty()) continue;
            dependencies.put(target.pos(), required);
            JsonObject row = new JsonObject(); row.add("offset", MachineAssemblyDocument.json(target.pos().subtract(anchor)));
            JsonArray needs = new JsonArray();
            for (BlockPos support : required) {
                // 承载依赖只决定先后顺序；漏斗最终形态由原生邻接结算，再交整机 diff，不能在这里预测拒绝设计。
                needs.add(MachineAssemblyDocument.json(support.subtract(anchor)));
            }
            row.add("requires", needs); dependencyReport.add(row);
        }
        placementDependencies = Map.copyOf(dependencies);
        attachmentLayers = MachinePlacementDependencies.layers(dependencies).stream().map(layer -> layer.stream().map(byPosition::get).toList()).toList();
        this.report.add("placement_dependencies", dependencyReport);
        this.report.addProperty("attachment_layers", attachmentLayers.size());
        List<Seal> closures = new ArrayList<>();
        if (report.has("seal_after_cleanup")) for (var element : report.getAsJsonArray("seal_after_cleanup")) {
            var closure = element.getAsJsonObject(); List<BuildTaskRecord.Target> targets = new ArrayList<>();
            for (var at : closure.getAsJsonArray("offsets")) {
                var target = byPosition.get(offset(anchor, at));
                if (target == null || target.desiredState().isAir() || isFluid(target)) throw new IllegalArgumentException("seal does not identify a final solid target");
                targets.add(target);
            }
            closures.add(new Seal(targets, offset(anchor, closure.get("outside_offset"))));
        }
        seals = List.copyOf(closures);
    }

    // 给布局编译器提供当前游戏的注册名和状态查询；不把“名字存在”当成“机器已经运行”。
    public static SemanticMachineLayout.Registry registry() {
        return new SemanticMachineLayout.Registry() {
            public boolean blockExists(String id) { return exists(id, true); }
            public boolean itemExists(String id) { return exists(id, false); }
            public boolean supportsState(String id, Map<String, String> properties) {
                try { MachinePlacementRules.resolveState(id, properties); return true; }
                catch (IllegalArgumentException unavailable) { return false; }
            }
            public MachineProcessingCapabilities processing(String id, Map<String, String> properties) {
                return CreateProcessingCapabilities.describe(MachinePlacementRules.resolveState(id, properties));
            }
            public boolean processingSpaceClear(String id, Map<String, String> properties) {
                return CreateProcessingCapabilities.openProcessingSpace(MachinePlacementRules.resolveState(id, properties));
            }
        };
    }

    private static boolean exists(String value, boolean block) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        return id != null && (block ? BuiltInRegistries.BLOCK.containsKey(id) : BuiltInRegistries.ITEM.containsKey(id));
    }

    /** 在设计审核阶段识别不受支持的原生安装方式，避免随后创建施工任务。 */
    // 用零锚点试编译一次，提前发现安装器不支持的结构；实际位置、已有障碍和材料以后才检查。
    public static SemanticMachineLayout.Result reviewExplicit(SemanticMachineLayout.Result layout) {
        if (!layout.buildable()) return layout;
        JsonObject report = layout.report().deepCopy();
        report.add("external_inputs",MachineUtilityInputs.json(
                MachineUtilityInputs.parse(layout.blueprint())));
        try {
            var compiled = compile(BlockPos.ZERO, layout, false);
            // 设计评审同时给出原生安装材料，源流体按真实满桶计费，而不是列出无法拿在手中的液体方块。
            report.add("native_material_counts", compiled.report.get("native_material_counts").deepCopy());
            report.addProperty("source_fluid_targets", compiled.fluidTargets().size());
            report.addProperty("native_installation_count", compiled.installations().size());
            report.addProperty("processing_relation_count", compiled.processing().size());
            report.addProperty("physical_target_count", compiled.positions().size());
            report.addProperty("native_installation_validated", true);
            report.addProperty("physical_layout_compiled", true);
            report.add("placement_dependencies", compiled.report.get("placement_dependencies").deepCopy());
            report.add("attachment_layers", compiled.report.get("attachment_layers").deepCopy());
            report.addProperty("site_and_material_preflight_pending", true);
            return new SemanticMachineLayout.Result(true, layout.blueprint(), report);
        } catch (IllegalArgumentException unsupported) {
            report.addProperty("buildable", false);
            report.addProperty("native_installation_validated", false);
            report.addProperty("physical_layout_compiled", false);
            JsonObject validation = report.getAsJsonObject("validation");
            validation.addProperty("valid", false);
            validation.getAsJsonArray("errors").add("unsupported_native_installation: " + unsupported.getMessage());
            validation.addProperty("error_count", validation.getAsJsonArray("errors").size());
            if (!validation.has("issues")) validation.add("issues", new JsonArray());
            validation.getAsJsonArray("issues").add(MachineDesignRejection.issue(unsupported.getMessage(), "goal.parameters.blueprint"));
            return new SemanticMachineLayout.Result(false, layout.blueprint(), report);
        }
    }

    public static MachineConstructionPlan compile(BlockPos anchor, SemanticMachineLayout.Result layout, boolean replace) {
        return compile(anchor, layout, replace, false);
    }

    public static MachineConstructionPlan compile(BlockPos anchor, SemanticMachineLayout.Result layout,
            boolean replace, boolean replaceBlockEntities) {
        if (replaceBlockEntities && !replace) throw new IllegalArgumentException("replace_block_entities requires replace_existing");
        if (!layout.buildable()) throw new IllegalArgumentException("machine layout is not executable: " + layout.report());
        // 原生操作与普通方块共用作者蓝图；先冻结安装原语，不能把整条带当成逐格放置。
        List<MachineNativeInstallation> installations = new ArrayList<>();
        var authored = MachineAssemblyDocument.blocks(layout.blueprint());
        for (var belt : MachineAssemblyDocument.belts(layout.blueprint())) installations.add(new CreateBeltInstallation(anchor, belt, authored));
        Map<BlockPos, BuildTaskRecord.Target> blocks = new LinkedHashMap<>();
        List<Part> parts = new ArrayList<>();
        Set<String> partSlots = new LinkedHashSet<>();
        Set<BlockPos> occupied = new LinkedHashSet<>();
        Set<Block> effectsChecked = new LinkedHashSet<>();
        for (JsonElement element : layout.blueprint().getAsJsonArray("blocks")) {
            JsonObject cell = element.getAsJsonObject();
            BlockPos position = offset(anchor, cell.get("offset"));
            if (cell.has("part")) {
                String side = cell.get("part").getAsString();
                Direction direction = side.equals("center") ? null : Direction.byName(side);
                if (!side.equals("center") && direction == null) throw new IllegalArgumentException("invalid AE part side");
                var spec = MachineInstallation.aePart(cell.get("item_id").getAsString(), direction);
                if (!partSlots.add(position.asLong() + ":" + side))
                    throw new IllegalArgumentException("duplicate native part target");
                parts.add(new Part(position, spec)); occupied.add(position);
                continue;
            }
            String id = cell.get("block_id").getAsString();
            Map<String, String> properties = new LinkedHashMap<>();
            if (cell.has("properties")) cell.getAsJsonObject("properties").entrySet()
                    .forEach(entry -> properties.put(entry.getKey(), entry.getValue().getAsString()));
            BlockState state = MachinePlacementRules.resolveState(id, properties);
            Block block = state.getBlock();
            var placementItem = MachinePlacementItems.itemFor(state);
            if (state.getBlock() instanceof LiquidBlock) {
                if (cell.has("nbt") && (!cell.get("nbt").isJsonObject() || !cell.getAsJsonObject("nbt").isEmpty()))
                    throw new IllegalArgumentException("source fluid placement does not accept copied NBT");
                // 即使作者省略 level，最终仍必须是源格，不能把后来流进来的同种非源流体算作完成。
                state.getProperties().forEach(property -> properties.put(property.getName(), state.getValue(property).toString()));
            }
            // 同一种方块只查一次连带结构规则；这里尚未读现场，因此已有同种机器也要先通过这项安装规则。
            if (effectsChecked.add(block)) MachinePlacementRules.requireModeledEffects(block);
            if (!occupied.add(position)) throw new IllegalArgumentException("overlapping machine targets");
            blocks.put(position, new BuildTaskRecord.Target(state, placementItem,
                    position, id, null, null, null, false, properties.keySet(), true));
        }
        // 原生动作的空闲路径是先决条件，不会自动转成拆除目标；清障必须由作者显式声明空气格。
        Map<BlockPos, BlockState> finalStates = new LinkedHashMap<>(); blocks.values().forEach(target -> finalStates.put(target.pos(), target.desiredState()));
        installations.forEach(step -> finalStates.putAll(step.targets()));
        List<MachineProcessingRelation> processing = new ArrayList<>();
        if (layout.blueprint().has("assembly")) for (var raw : layout.blueprint().getAsJsonObject("assembly").getAsJsonArray("processing")) {
            var row = raw.getAsJsonObject(); var relation = MachineProcessingRelation.compile(offset(anchor, row.get("processor")), offset(anchor, row.get("surface")), finalStates);
            processing.add(relation);
        }
        // 即使蓝图由模型编写，自动生成的半方块也必须显式声明。
        Map<Long, BuildTaskRecord.Target> cells = new LinkedHashMap<>();
        blocks.values().forEach(target -> cells.put(target.pos().asLong(), target));
        blocks.values().forEach(target -> MachinePlacementRules.validateGeneratedCells(target, cells));
        JsonObject report = layout.report().deepCopy();
        if (report.has("configurations") && !report.getAsJsonArray("configurations").isEmpty()) {
            if (!exists("mekanism:configurator", false))
                throw new IllegalArgumentException("native interface configuration requires an installed Mekanism configurator");
            var tools = new JsonArray(); tools.add("mekanism:configurator");
            report.add("required_tools", tools);
        }
        // 维护空间会变成明确的空气目标，不能与设备目标重叠；它不是单纯给画面看的标记。
        if (report.has("clearance_cells")) for (JsonElement cell : report.getAsJsonArray("clearance_cells")) {
            BlockPos position = offset(anchor, cell);
            if (occupied.contains(position) && (blocks.get(position) == null || !blocks.get(position).desiredState().isAir()))
                throw new IllegalArgumentException("maintenance clearance overlaps equipment");
            blocks.putIfAbsent(position, new BuildTaskRecord.Target(Blocks.AIR.defaultBlockState(), Items.AIR,
                    position, "machine maintenance clearance", null, null, null, false, Set.of(), true));
        }
        List<BlockPos> components = new ArrayList<>();
        if (report.has("components")) for (JsonElement component : report.getAsJsonArray("components")) {
            JsonObject entry = component.getAsJsonObject();
            if (entry.has("offset")) components.add(offset(anchor, entry.get("offset")));
        }
        if (components.isEmpty()) blocks.values().stream().filter(target -> !target.desiredState().isAir())
                .map(BuildTaskRecord.Target::pos).forEach(components::add);
        if (blocks.size() + parts.size() > SemanticMachineLayout.MAX_TARGETS)
            throw new IllegalArgumentException("expanded machine exceeds the physical planning budget");
        for (Part part : parts) if (blocks.containsKey(part.position()))
            throw new IllegalArgumentException("native part host overlaps an ordinary block target");
        // 中心线缆为外围部件提供支撑，因此必须始终先安装。
        // 先排中心部件，再排装在各面的部件，避免面板先安装时还没有宿主。
        parts.sort(Comparator.comparing(part -> part.spec().side() != null));
        Map<String, Integer> nativeMaterials = new LinkedHashMap<>();
        blocks.values().forEach(target -> { int count = isFluid(target) ? 1 : target.materialCount();
            if (count > 0) nativeMaterials.merge(BuiltInRegistries.ITEM.getKey(target.item()).toString(), count, Math::addExact); });
        parts.forEach(part -> nativeMaterials.merge(part.spec().itemId(), 1, Math::addExact));
        installations.forEach(step -> step.materials().forEach((item, count) -> nativeMaterials.merge(item.toString(), count, Math::addExact)));
        if (report.has("initial_contents")) report.getAsJsonArray("initial_contents").forEach(raw -> {
            var value = raw.getAsJsonObject(); nativeMaterials.merge(value.get("item_id").getAsString(), value.get("count").getAsInt(), Math::addExact); });
        JsonObject materialCounts = new JsonObject(); nativeMaterials.forEach(materialCounts::addProperty);
        report.add("native_material_counts", materialCounts);
        MachineDesignConstraints.verifyMaterials(layout.blueprint(), report);
        report.addProperty("source_fluid_targets", blocks.values().stream().filter(MachineConstructionPlan::isFluid).count());
        report.addProperty("native_material_scope", "full installation upper bound; already matching blocks and source fluids are reused");
        return new MachineConstructionPlan(anchor, new ArrayList<>(blocks.values()), parts, components, report, replace, replaceBlockEntities, installations, processing, layout.blueprint());
    }

    /** 勘查锚点表示安装地面，包括机器下方的驱动装置。 */
    // 按蓝图最低偏移向上抬锚点，让整个计划最低层落在勘察到的地板高度；维护空间也参与计算。
    public static BlockPos floorAnchor(BlockPos surveyed, SemanticMachineLayout.Result layout) {
        int lowest = 0;
        for (var element : layout.blueprint().getAsJsonArray("blocks"))
            lowest = Math.min(lowest, element.getAsJsonObject().getAsJsonArray("offset").get(1).getAsInt());
        if (layout.report().has("clearance_cells")) for (var element : layout.report().getAsJsonArray("clearance_cells"))
            lowest = Math.min(lowest, element.getAsJsonArray().get(1).getAsInt());
        return surveyed.above(-lowest);
    }

    static BlockPos offset(BlockPos anchor, JsonElement element) {
        var a = element.getAsJsonArray();
        if (a.size() != 3) throw new IllegalArgumentException("internal machine offset needs three axes");
        return new BlockPos(Math.addExact(anchor.getX(), a.get(0).getAsBigDecimal().intValueExact()),
                Math.addExact(anchor.getY(), a.get(1).getAsBigDecimal().intValueExact()),
                Math.addExact(anchor.getZ(), a.get(2).getAsBigDecimal().intValueExact()));
    }

    public BuildTaskRecord blockTask(String callId, long deadline, boolean consume) {
        return blockTask(callId, deadline, consume, List.of());
    }

    public BuildTaskRecord blockTask(String callId, long deadline, boolean consume, List<BlockPos> partClears) {
        return blockTask(callId, deadline, consume, partClears, Set.of());
    }

    // 普通施工先保留临时洞口为空，再加入为部件腾位的清空目标；封洞另在角色走到外面之后完成。
    public BuildTaskRecord blockTask(String callId, long deadline, boolean consume, List<BlockPos> partClears, Set<BlockPos> openings) {
        return blockTask(callId, deadline, consume, partClears, openings, Set.of());
    }
    public BuildTaskRecord blockTask(String callId, long deadline, boolean consume, List<BlockPos> partClears, Set<BlockPos> openings, Set<BlockPos> completedInstallations) {
        List<BuildTaskRecord.Target> placement = new ArrayList<>();
        // 源流体留给封洞后的桶操作，不能变成空气施工目标而把已经正确的水源重新挖掉。
        for (var target : blocks) if (!isFluid(target) && !placementDependencies.containsKey(target.pos()) && !completedInstallations.contains(target.pos())) placement.add(openings.contains(target.pos())
                ? new BuildTaskRecord.Target(Blocks.AIR.defaultBlockState(), Items.AIR, target.pos(),
                    "temporary machine entrance", null, null, null, false, Set.of(), true)
                : placementTarget(target));
        for (BlockPos at : partClears) placement.add(new BuildTaskRecord.Target(Blocks.AIR.defaultBlockState(),
                Items.AIR, at, "native installation preparation", null, null, null, false, Set.of(), true));
        BuildTaskRecord task = new BuildTaskRecord(callId, deadline, placement,
                replace ? ReplaceMode.REPLACE_EMPTY : ReplaceMode.DONT_REPLACE, replace, consume,
                consume, Map.of(), List.of(), replaceBlockEntities);
        task.previewManaged(true);
        if (fixedModification) task.machineModification(observedEdits);
        else if (!ownedReplacements.isEmpty()) task.machineModification(ownedReplacements);
        // 作者明确允许替换时，蓝图点名的格子直接继承许可；新建入口续建也不要求旧观察状态完全一致。
        if (replace) task.automaticMachineModification(authoredModificationCells());
        task.futureWorkItems(foodProtectedWorkItems());
        var protectedSources = new ArrayList<>(parts.stream().map(Part::position).toList());
        fluidTargets().forEach(target -> protectedSources.add(target.pos())); task.materialSupplyProtection(protectedSources);
        task.semanticFacts(Map.of("machine_geometry_verified", parts.isEmpty() && openings.isEmpty() && fluidTargets().isEmpty(), "machine_production_verified", false));
        return task;
    }

    /** 原生邻接生成的形态留给整机差异，动作回执只核对本次实际放置属性。 */
    private static BuildTaskRecord.Target placementTarget(BuildTaskRecord.Target target) {
        String id = BuiltInRegistries.BLOCK.getKey(target.block()).toString();
        Set<String> properties = new LinkedHashSet<>(target.exactProperties());
        if (Set.of("create:brass_tunnel", "create:andesite_tunnel", "create:brass_belt_funnel", "create:andesite_belt_funnel").contains(id)) {
            // 隧道和带漏斗会按相邻皮带改变 shape；原生放下同向部件是明确效果，保留原图供随后比较形态差异。
            properties.remove("shape");
            Set<String> finals = new LinkedHashSet<>(target.finalProperties() == null ? properties : target.finalProperties());
            finals.remove("shape");
            return new BuildTaskRecord.Target(target.desiredState(), target.item(), target.pos(), target.label(),
                    target.facing(), target.axis(), target.topHalf(), false, properties, true, finals);
        }
        if (!id.equals("create:encased_chain_drive")) return target;
        // 链式传动箱的邻接状态也由游戏生成；完整图纸仍保留原声明，施工结束后照常报告差异。
        properties.remove("axis_along_first"); properties.remove("part");
        return new BuildTaskRecord.Target(target.desiredState(), target.item(), target.pos(), target.label(),
                target.facing(), target.axis(), target.topHalf(), false, properties, true);
    }

    public Map<BlockPos, BlockState> preview() {
        return finalStates;
    }
    public Map<BlockPos, List<BlockPos>> placementDependencies() { return placementDependencies; }
    public List<List<BuildTaskRecord.Target>> attachmentLayers() { return attachmentLayers; }
    public void validatePlacementDependency(Level world, BlockPos at) {
        // 只确认承载格能被真实读取，不以预测的漏斗形态替代本次原生放置和事后状态观察。
        for (BlockPos support : placementDependencies.getOrDefault(at, List.of()))
            if (!world.isLoaded(support)) throw new IllegalArgumentException("placement_support_unloaded: " + support);
    }
    public BuildTaskRecord attachmentTask(int index, String callId, long deadline, boolean consume) {
        // 附件仍走已有的生存放置、材料补给和真实回执；所有已建成的计划格都保护为补料禁挖区。
        var placement = attachmentLayers.get(index).stream().map(MachineConstructionPlan::placementTarget).toList();
        var task = new BuildTaskRecord(callId, deadline, placement, replace ? ReplaceMode.REPLACE_EMPTY : ReplaceMode.DONT_REPLACE,
                replace, consume, consume, Map.of(), List.of(), replaceBlockEntities);
        // 本层附件格必须允许执行作者声明的拆换；补料器还会把 task.targets 加回禁挖范围，不会借取料拆掉待改部件。
        var activeCells=placement.stream().map(BuildTaskRecord.Target::pos).collect(Collectors.toSet());
        task.previewManaged(true); task.materialSupplyProtection(positions().stream().filter(at->!activeCells.contains(at)).toList());
        if (fixedModification) task.machineModification(observedEdits);
        else if (!ownedReplacements.isEmpty()) task.machineModification(ownedReplacements);
        // 后置附件使用同一份声明范围，不能因进入另一个阶段又回到旧快照的逐格准入门控。
        if (replace) task.automaticMachineModification(authoredModificationCells());
        task.futureWorkItems(foodProtectedWorkItems());
        task.semanticFacts(Map.of("machine_geometry_verified", false, "machine_production_verified", false)); return task;
    }
    public BlockPos anchor() { return anchor; }
    /** 机器修改只豁免作者明确点名且本次勘察范围内已加载的旧方块，不给通路清障或蓝图隐含净空扩权。 */
    public void bindObservedModification(Level world, BlockPos surveyedCenter, int radius) {
        var observed = new LinkedHashMap<BlockPos, BlockState>();
        for (BlockPos offset : MachineAssemblyDocument.blocks(blueprint()).keySet()) {
            BlockPos at = anchor.offset(offset);
            if (Math.abs((long) at.getX() - surveyedCenter.getX()) > radius
                    || Math.abs((long) at.getY() - surveyedCenter.getY()) > radius
                    || Math.abs((long) at.getZ() - surveyedCenter.getZ()) > radius || !world.isLoaded(at)) continue;
            var state = world.getBlockState(at);
            if (!state.isAir()) observed.put(at.immutable(), state);
        }
        observedEdits = Map.copyOf(observed); fixedModification = true;
    }
    /** 修改已指定的坐标时内部读取这些格子，不扫描无关整片场地，也不让 LLM 为加载后的格子再次申请观察。 */
    public void bindAutomaticModification(Level world) {
        automaticModification = true; bindObservedModification(world, anchor, Integer.MAX_VALUE);
    }

    /** 局部改造即使要求保留旧块，也必须合回原整机档案；档案合并不授予任何拆除权限。 */
    public void markModification() { declaredModification = true; }

    /** 新建入口明确允许替换时，也复用本方原生放置记录；只绑定蓝图点名的旧部件，不扩大到通路或邻居。 */
    public void bindOwnedReplacements(LocalPlayer player) {
        bindOwnedReplacements(player.level(), (at, state) -> ConstructionOwnership.owns(player, at, state));
    }
    void bindOwnedReplacements(Level world, BiPredicate<BlockPos, BlockState> owns) {
        if (!replace) return;
        var owned = new LinkedHashMap<BlockPos, BlockState>();
        for (BlockPos at : authoredModificationCells()) {
            if (!world.isLoaded(at)) continue;
            var state = world.getBlockState(at);
            if (!state.isAir() && owns.test(at, state)) owned.put(at.immutable(), state);
        }
        // 子施工保留这组原位状态，避免再建议平移已建机器；新建的完整图纸归档语义保持原样。
        ownedReplacements = Map.copyOf(owned);
        report.addProperty("owned_replacement_targets", owned.size());
    }
    private Set<BlockPos> authoredModificationCells() {
        // assembly 声明的整条皮带与逐格方块同属本次施工范围；中间格的旧垫块不需要模型再写一遍空气目标。
        Set<BlockPos> declared = MachineAssemblyDocument.blocks(blueprint()).keySet().stream()
                .map(anchor::offset).collect(Collectors.toCollection(LinkedHashSet::new));
        declared.addAll(nativePositions);
        return Set.copyOf(declared);
    }
    /** 放块阶段也保留后续皮带连接器、附件和显式工序原料，防止提前吃掉例如皮带配方中的熟海带。 */
    /** 同一份后续材料账用于补食和整理背包，不能把待装传送带或待投原料存成无关余料。 */
    public Set<Item> workItems() { return foodProtectedWorkItems(); }

    private Set<Item> foodProtectedWorkItems() {
        var items = new LinkedHashSet<Item>();
        blocks.forEach(target -> { if (target.materialCount() > 0) items.add(target.item()); });
        parts.forEach(part -> items.add(part.spec().item()));
        installations.forEach(step -> step.materials().keySet().forEach(id -> items.add(BuiltInRegistries.ITEM.get(id))));
        collectWorkItems(blueprint(), items);
        items.remove(Items.AIR); return Set.copyOf(items);
    }
    private static void collectWorkItems(JsonElement value, Set<Item> items) {
        // 这里只读取图纸中已声明的物品标识，不根据自然语言猜测未来需求。
        if (value.isJsonObject()) value.getAsJsonObject().entrySet().forEach(entry -> collectWorkItems(entry.getValue(), items));
        else if (value.isJsonArray()) value.getAsJsonArray().forEach(entry -> collectWorkItems(entry, items));
        else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            var id = ResourceLocation.tryParse(value.getAsString());
            if (id != null && BuiltInRegistries.ITEM.containsKey(id)) items.add(BuiltInRegistries.ITEM.get(id));
        }
    }
    public String blueprintJson() { return blueprintJson; }
    public JsonObject blueprint() { return JsonParser.parseString(blueprintJson).getAsJsonObject(); }
    // 完工归档据此把局部拆换合回旧设计；普通新建仍保存完整新图，不能把两种语义混在一起。
    public boolean modification() { return declaredModification || fixedModification || automaticModification; }
    public List<BuildTaskRecord.Target> blocks() { return blocks; }
    public List<BuildTaskRecord.Target> fluidTargets() { return fluidTargets; }
    public static boolean isFluid(BuildTaskRecord.Target target) { return target.desiredState().getBlock() instanceof LiquidBlock; }
    public List<Part> parts() { return parts; }
    public List<MachineNativeInstallation> installations() { return installations; }
    public List<MachineProcessingRelation> processing() { return processing; }
    public Set<BlockPos> nativePositions() { return nativePositions; }
    public List<Seal> seals() { return seals; }
    // 找出临时洞口内外要留给身体通行的空气格，供普通施工阶段保护。
    public List<BlockPos> constructionAccess(BuildTaskRecord bulk) {
        Set<BlockPos> passage = new LinkedHashSet<>();
        for (Seal seal : seals) for (var door : seal.targets()) {
            passage.add(door.pos()); passage.add(door.pos().relative(seal.outward()));
            passage.add(door.pos().relative(seal.outward().getOpposite()));
        }
        return bulk.targets.stream().filter(target -> target.desiredState().isAir() && passage.contains(target.pos()))
                .map(BuildTaskRecord.Target::pos).toList();
    }
    public List<BlockPos> components() { return components; }
    public boolean replaceExisting() { return replace; }
    public boolean replaceBlockEntities() { return replaceBlockEntities; }
    public List<BlockPos> positions() {
        Set<BlockPos> positions = new LinkedHashSet<>();
        blocks.forEach(target -> positions.add(target.pos())); parts.forEach(part -> positions.add(part.position()));
        installations.forEach(step -> positions.addAll(step.targets().keySet()));
        processing.forEach(relation -> positions.addAll(relation.clearance()));
        placementDependencies.values().forEach(positions::addAll);
        return List.copyOf(positions);
    }
    public JsonObject report() { return report.deepCopy(); }
    public List<MachineUtilityInputs.Input> utilityInputs() {
        if (!report.has("external_inputs")) return List.of();
        return MachineUtilityInputs.parseDeclarations(report.getAsJsonArray("external_inputs"));
    }
}
