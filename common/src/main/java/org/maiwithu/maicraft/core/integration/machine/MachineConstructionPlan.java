// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
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
import org.maiwithu.maicraft.core.integration.create.CreateFunnelPlacement;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.core.integration.machine.assembly.MachineNativeInstallation;

/**
 * 把机器布局或逐格蓝图变成固定的装配计划：普通方块、AE2 部件、维护通道，以及最后要封闭的施工洞口。
 * 这里只决定要做什么，还没有观察每一格现场或安排角色动作。
 */
public final class MachineConstructionPlan {
    public record Part(BlockPos position, MachineInstallation.PartSpec spec) {}
    private final BlockPos anchor;
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

    private MachineConstructionPlan(BlockPos anchor, List<BuildTaskRecord.Target> blocks,
            List<Part> parts, List<BlockPos> components, JsonObject report, boolean replace, boolean replaceBlockEntities,
            List<MachineNativeInstallation> installations, List<MachineProcessingRelation> processing) {
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
            var required = CreateFunnelPlacement.dependencies(target.desiredState()).stream().map(target.pos()::offset).toList();
            if (required.isEmpty()) continue;
            dependencies.put(target.pos(), required);
            JsonObject row = new JsonObject(); row.add("offset", MachineAssemblyDocument.json(target.pos().subtract(anchor)));
            JsonArray needs = new JsonArray();
            for (BlockPos support : required) {
                if (finals.containsKey(support)) CreateFunnelPlacement.validate(target.desiredState(), finals.get(support));
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

    /** Catch unsupported native installations during design review, before requesting a construction task. */
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
        // Generated halves must be declared even for model-authored blueprints.
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
        // Center cables form supports for peripheral parts and must always be installed first.
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
        return new MachineConstructionPlan(anchor, new ArrayList<>(blocks.values()), parts, components, report, replace, replaceBlockEntities, installations, processing);
    }

    /** The survey anchor denotes the floor of the installation, including below-machine drives. */
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
        var protectedSources = new ArrayList<>(parts.stream().map(Part::position).toList());
        fluidTargets().forEach(target -> protectedSources.add(target.pos())); task.materialSupplyProtection(protectedSources);
        task.semanticFacts(Map.of("machine_geometry_verified", parts.isEmpty() && openings.isEmpty() && fluidTargets().isEmpty(), "machine_production_verified", false));
        return task;
    }

    /** Chain orientation is derived from neighbors, so only its shaft axis is enforced during assembly. */
    // Create 链式传动箱的连接状态由相邻方块生成，初次放置先放宽这两项；原始计划仍保留，最后按原要求验收。
    private static BuildTaskRecord.Target placementTarget(BuildTaskRecord.Target target) {
        if (!BuiltInRegistries.BLOCK.getKey(target.block()).toString().equals("create:encased_chain_drive")) return target;
        Set<String> properties = new LinkedHashSet<>(target.exactProperties());
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
        for (BlockPos support : placementDependencies.getOrDefault(at, List.of()))
            CreateFunnelPlacement.validate(targetsByPosition.get(at).desiredState(), world.getBlockState(support));
    }
    public BuildTaskRecord attachmentTask(int index, String callId, long deadline, boolean consume) {
        // 附件仍走已有的生存放置、材料补给和真实回执；所有已建成的计划格都保护为补料禁挖区。
        var task = new BuildTaskRecord(callId, deadline, attachmentLayers.get(index), replace ? ReplaceMode.REPLACE_EMPTY : ReplaceMode.DONT_REPLACE,
                replace, consume, consume, Map.of(), List.of(), replaceBlockEntities);
        task.previewManaged(true); task.materialSupplyProtection(positions());
        task.semanticFacts(Map.of("machine_geometry_verified", false, "machine_production_verified", false)); return task;
    }
    public BlockPos anchor() { return anchor; }
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
