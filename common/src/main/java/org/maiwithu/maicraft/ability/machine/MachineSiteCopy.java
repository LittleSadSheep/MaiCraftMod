// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import org.maiwithu.maicraft.ability.machine.spi.Installation;
import org.maiwithu.maicraft.ability.machine.spi.MachineCopy;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.ability.machine.spi.PartCell;
import org.maiwithu.maicraft.behavior.construction.BlockStateRules;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;

/**
 * 读现场把一台机器抄成机器蓝图，纯函数：读到的格由调用方交进来，抄出来的是 machine_build 认的那一份蓝图
 * （逐格方块、安装段、部件），偏移相对锚点。
 *
 * <p>包围盒里每一格非空的：被机器类型认领、而且这种机器有自己的抄法的（传送带、链、机械臂、ME 线缆）照它给的写；
 * 水源、岩浆源写成倒桶；流动的水不写（源格倒下去它自己会流）；其余照抄方块，先按施工的归一规则去掉运行态，
 * 转起来就会变的属性（开关、充能、亮灭、在不在线）也不写，其余属性全写进去当验收标准。
 * 空气不写：蓝图没声明的格不是"必须为空"，照着建时那些格里原有的东西会留着。
 */
final class MachineSiteCopy {

    /**
     * 不写进蓝图的属性：机器一转、红石一通就会变，写进去照着建完一比对全是"状态不对"。
     * 含水、伸出在施工的归一规则里已经去掉，这里一并不写；AE2 控制器的 state 是在线、离线、摆法冲突。
     */
    static final Set<String> RUNNING_PROPERTIES = Set.of("powered", "lit", "active", "open", "enabled", "triggered",
            "occupied", "power", "locked", "state", "waterlogged", "extended");

    private MachineSiteCopy() {
    }

    /**
     * 抄出来的那一份。
     *
     * @param anchor    锚点，偏移的原点
     * @param size      包围盒三边各几格
     * @param blueprint 抄出来的机器蓝图（偏移相对锚点）
     * @param json      同一份蓝图写成 machine_build 的 blueprint 参数，可以原样喂回去
     * @param unread    包围盒里没读到的格数（没加载）
     * @param flowing   没写的流动液体格数
     * @param notes     抄不全、照着建会走样的地方
     */
    record Result(BlockPos anchor, BlockPos size, MachineBlueprint blueprint, JsonObject json, int unread, int flowing,
                  List<String> notes) {

        Result {
            notes = List.copyOf(notes);
        }
    }

    /** 抄到一半的账：要写成方块的格、交给机器自己抄过的格、安装段、部件、说明。 */
    private static final class Draft {
        final Map<BlockPos, BlockState> written = new LinkedHashMap<>();
        final Set<BlockPos> handled = new LinkedHashSet<>();
        final List<MachineBlueprint.Segment> segments = new ArrayList<>();
        final List<MachineBlueprint.Part> parts = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
    }

    /**
     * @param anchor   锚点：包围盒最小角，指档案时是档案的锚点
     * @param min      包围盒最小角
     * @param max      包围盒最大角
     * @param world    一格现在是什么；没加载给空，不按空气算，记进 unread
     * @param claiming 认领这一格的机器类型；没有为 null
     */
    static Result copy(BlockPos anchor, BlockPos min, BlockPos max, Function<BlockPos, Optional<BlockState>> world,
            Function<BlockState, MachineType> claiming) {
        Map<BlockPos, BlockState> inBox = new LinkedHashMap<>();
        int unread = 0;
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            BlockPos at = pos.immutable();
            Optional<BlockState> state = world.apply(at);
            if (state.isEmpty()) {
                unread++;
            } else if (!state.get().isAir()) {
                inBox.put(at, state.get());
            }
        }
        // 有自己抄法的机器先抄，剩下的格照抄方块；再逐格写成计划格与蓝图参数。
        Draft draft = new Draft();
        copyOwnWays(draft, inBox, anchor, claiming);
        inBox.forEach((at, state) -> {
            if (!draft.handled.contains(at)) draft.written.put(at, state);
        });
        List<PlannedCell> cells = new ArrayList<>();
        JsonArray cellsJson = new JsonArray();
        int flowing = writeCells(draft.written, anchor, cells, cellsJson);
        MachineBlueprint blueprint = new MachineBlueprint(cells, draft.parts, draft.segments, List.of(), List.of());
        JsonObject json = new JsonObject();
        json.add("cells", cellsJson);
        if (!draft.segments.isEmpty()) json.add("installations", installationsJson(draft.segments));
        if (!draft.parts.isEmpty()) json.add("parts", partsJson(draft.parts));
        BlockPos size = new BlockPos(max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1);
        return new Result(anchor, size, blueprint, json, unread, flowing, draft.notes);
    }

    // 按认领它的机器类型分组：有自己抄法的那几种（传送带、链、机械臂、ME 线缆）整组交给它，照它给的写。
    private static void copyOwnWays(Draft draft, Map<BlockPos, BlockState> inBox, BlockPos anchor,
            Function<BlockState, MachineType> claiming) {
        Map<MachineType, Map<BlockPos, BlockState>> byType = new IdentityHashMap<>();
        List<MachineType> typeOrder = new ArrayList<>();
        inBox.forEach((at, state) -> {
            MachineType type = claiming.apply(state);
            if (type == null) return;
            if (!byType.containsKey(type)) typeOrder.add(type);
            byType.computeIfAbsent(type, key -> new LinkedHashMap<>()).put(at, state);
        });
        for (MachineType type : typeOrder) {
            Map<BlockPos, BlockState> cells = byType.get(type);
            Optional<MachineCopy> own = type.copy(cells);
            if (own.isEmpty()) continue;
            draft.handled.addAll(cells.keySet());
            draft.written.putAll(own.get().blocks());
            for (Installation installation : own.get().installations()) {
                draft.segments.add(new MachineBlueprint.Segment(installation.kind(),
                        installation.cells().stream().map(cell -> cell.subtract(anchor)).toList(), installation.roles()));
            }
            for (PartCell part : own.get().parts()) {
                draft.parts.add(new MachineBlueprint.Part(part.host().subtract(anchor), part.side(), part.itemId()));
            }
            draft.notes.addAll(own.get().notes());
        }
    }

    // 逐格写：流动的液体不写，水源岩浆源写成倒桶，其余去掉运行态后照抄；返回没写的流动液体格数。
    private static int writeCells(Map<BlockPos, BlockState> written, BlockPos anchor, List<PlannedCell> cells,
            JsonArray cellsJson) {
        int flowing = 0;
        for (BlockPos at : sorted(written.keySet())) {
            BlockState state = written.get(at);
            if (state.getBlock() instanceof LiquidBlock) {
                if (!state.getFluidState().isSource()) {
                    flowing++;
                    continue;
                }
                state = state.getBlock().defaultBlockState();
            }
            BlockState kept = BlockStateRules.normalize(state);
            BlockPos offset = at.subtract(anchor);
            Set<String> required = new LinkedHashSet<>();
            JsonObject properties = new JsonObject();
            if (!(kept.getBlock() instanceof LiquidBlock)) {
                for (Property<?> property : kept.getProperties()) {
                    if (RUNNING_PROPERTIES.contains(property.getName())) continue;
                    required.add(property.getName());
                    properties.addProperty(property.getName(), valueName(kept, property));
                }
            }
            cells.add(PlannedCell.block(offset, kept, required));
            JsonObject cell = new JsonObject();
            cell.add("offset", vector(offset));
            cell.addProperty("block", BuiltInRegistries.BLOCK.getKey(kept.getBlock()).toString());
            if (!properties.isEmpty()) cell.add("properties", properties);
            cellsJson.add(cell);
        }
        return flowing;
    }

    // 安装段写成蓝图参数：kind、offsets 每格一个 [x, y, z]，分角色的再写 roles。
    private static JsonArray installationsJson(List<MachineBlueprint.Segment> segments) {
        JsonArray out = new JsonArray();
        for (MachineBlueprint.Segment segment : segments) {
            JsonObject json = new JsonObject();
            json.addProperty("kind", segment.kind());
            JsonArray offsets = new JsonArray();
            segment.offsets().forEach(offset -> offsets.add(vector(offset)));
            json.add("offsets", offsets);
            if (!segment.roles().isEmpty()) {
                JsonArray roles = new JsonArray();
                segment.roles().forEach(roles::add);
                json.add("roles", roles);
            }
            out.add(json);
        }
        return out;
    }

    // 部件写成蓝图参数：宿主 offset、装在哪一面、部件物品。
    private static JsonArray partsJson(List<MachineBlueprint.Part> parts) {
        JsonArray out = new JsonArray();
        for (MachineBlueprint.Part part : parts) {
            JsonObject json = new JsonObject();
            json.add("offset", vector(part.offset()));
            json.addProperty("side", part.side().getName());
            json.addProperty("item", part.itemId());
            out.add(json);
        }
        return out;
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    static JsonArray vector(BlockPos offset) {
        JsonArray out = new JsonArray();
        out.add(offset.getX());
        out.add(offset.getY());
        out.add(offset.getZ());
        return out;
    }

    // 按 y、z、x 排：从下往上一层层读，每次抄出来顺序一样。
    private static List<BlockPos> sorted(Set<BlockPos> cells) {
        return cells.stream().sorted(Comparator.comparingInt((BlockPos pos) -> pos.getY()).thenComparingInt(pos -> pos.getZ())
                .thenComparingInt(pos -> pos.getX())).toList();
    }
}
