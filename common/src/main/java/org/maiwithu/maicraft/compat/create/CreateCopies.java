// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

import org.maiwithu.maicraft.ability.machine.spi.Installation;
import org.maiwithu.maicraft.ability.machine.spi.MachineCopy;

/**
 * 读现场时机械动力那几种用自己方式装出来的东西怎么抄回机器蓝图，纯函数：读数由调用方从现场读好交进来。
 *
 * <p>传送带：铺带是拿传送带先点一根轴、再点另一根轴，两头的轴（和中间同轴向的轴）变成带轮，所以抄回去时
 * 两头与带轮写成与带子垂直的轴，整条带写成一段从带头数起的安装段。链条输送机：方块照抄，两台之间的链写成一段，
 * 连到这台机器外面的那一头可能是主城的东西，只写进说明。机械臂：选好取放点再放下，所以机械臂不写成方块，
 * 写成一段"机械臂加取放点"；没选过点的机械臂就是一个普通方块。
 */
final class CreateCopies {

    private CreateCopies() {
    }

    /**
     * 一格传送带此刻的样子。
     *
     * @param segment 属于哪一条、第几格、是带头带尾还是带轮
     * @param axis    这条带的轴向：两头写回轴时用
     * @param encased 包没包机壳
     */
    record BeltCell(CreateBelts.Segment segment, Direction.Axis axis, boolean encased) {
    }

    /**
     * 抄传送带：按控制格把格归成一条条，每条按序号排好写成一段 create:belt；带头、带尾、带轮写成轴。
     * 一条带有格不在交来的格里（伸出了这台机器）或读不到的，不抄成安装段，写进说明。
     *
     * @param cells  这台机器里传送带认领的格
     * @param read   一格传送带的读数；读不到给空
     * @param shafts 某个轴向的轴的方块状态；没有 Create 的轴给空
     */
    static MachineCopy belts(Set<BlockPos> cells, Function<BlockPos, Optional<BeltCell>> read,
            Function<Direction.Axis, Optional<BlockState>> shafts) {
        Map<BlockPos, List<Map.Entry<BlockPos, BeltCell>>> byBelt = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        for (BlockPos cell : sorted(cells)) {
            Optional<BeltCell> belt = read.apply(cell);
            if (belt.isEmpty()) {
                notes.add(cell.toShortString() + " 读不到传送带的样子，没抄");
                continue;
            }
            byBelt.computeIfAbsent(belt.get().segment().controller(), key -> new ArrayList<>())
                    .add(Map.entry(cell, belt.get()));
        }
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        List<Installation> installations = new ArrayList<>();
        boolean encased = false;
        for (var belt : byBelt.entrySet()) {
            List<Map.Entry<BlockPos, BeltCell>> pieces = belt.getValue();
            pieces.sort(Comparator.comparingInt(piece -> piece.getValue().segment().index()));
            int length = pieces.getFirst().getValue().segment().length();
            if (!complete(pieces, length)) {
                notes.add("从 " + belt.getKey().toShortString() + " 起的那条传送带有 " + length + " 格，这台机器里只有 "
                        + pieces.size() + " 格，没抄成安装段");
                continue;
            }
            // 带头、带尾、带轮原来都是轴：先放轴，再拿传送带点两头，Create 会把中间同轴向的轴一起变成带轮。
            for (var piece : pieces) {
                BeltCell reading = piece.getValue();
                encased |= reading.encased();
                if (reading.segment().part().equals("middle")) continue;
                Optional<BlockState> shaft = shafts.apply(reading.axis());
                if (shaft.isEmpty()) {
                    notes.add(piece.getKey().toShortString() + " 原来是一根轴，读不到 Create 的轴方块，没写");
                    continue;
                }
                blocks.put(piece.getKey(), shaft.get());
            }
            installations.add(new Installation(BeltInstallation.KIND, pieces.stream().map(Map.Entry::getKey).toList()));
        }
        if (encased) notes.add("有传送带包着机壳，抄成没包的：照着建完再手持机壳点上");
        return new MachineCopy(blocks, installations, List.of(), notes);
    }

    // 一条带的格按序号从 0 排到 length-1，一格不少才抄得成。
    private static boolean complete(List<Map.Entry<BlockPos, BeltCell>> pieces, int length) {
        if (pieces.size() != length) return false;
        for (int i = 0; i < pieces.size(); i++) {
            if (pieces.get(i).getValue().segment().index() != i) return false;
        }
        return true;
    }

    /**
     * 传送带的轴向：横躺在侧面的带（sideways）轴是竖的，其余是带子朝向顺时针转一下的那根水平轴。
     * Create 自己判带轮轴向就是这条规则（拆带子时带轮也按它变回轴）。
     */
    static Optional<Direction.Axis> beltAxis(BlockState belt) {
        if (!belt.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return Optional.empty();
        Property<?> slope = belt.getBlock().getStateDefinition().getProperty("slope");
        if (slope != null && valueName(belt, slope).equals("sideways")) return Optional.of(Direction.Axis.Y);
        return Optional.of(belt.getValue(BlockStateProperties.HORIZONTAL_FACING).getClockWise().getAxis());
    }

    /** 传送带包没包机壳（casing 属性）。 */
    static boolean beltEncased(BlockState belt) {
        Property<?> casing = belt.getBlock().getStateDefinition().getProperty("casing");
        return casing != null && valueName(belt, casing).equals("true");
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    /**
     * 抄链条输送机：方块照抄；两头都在这台机器里的每一条链写成一段 create:chain_conveyor（每条只写一次）；
     * 连到外面的写进说明，照着建时那一头没有链条输送机就连不上。
     *
     * @param states 这台机器里链条输送机的格与方块状态
     * @param links  一台链条输送机连着哪几台（相对它自己的位置）；读不到给空
     */
    static MachineCopy chains(Map<BlockPos, BlockState> states, Function<BlockPos, Optional<Set<BlockPos>>> links) {
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        List<Installation> installations = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (BlockPos cell : sorted(states.keySet())) {
            blocks.put(cell, states.get(cell));
            for (BlockPos relative : sorted(links.apply(cell).orElse(Set.of()))) {
                BlockPos other = cell.offset(relative);
                if (!states.containsKey(other)) {
                    notes.add("链条输送机 " + cell.toShortString() + " 连到这台机器外面的 " + other.toShortString()
                            + "，这条链没抄：照着建时那一头得先有一台链条输送机");
                } else if (cell.asLong() < other.asLong()) {
                    installations.add(new Installation(ChainConveyorInstallation.KIND, List.of(cell, other)));
                }
            }
        }
        return new MachineCopy(blocks, installations, List.of(), notes);
    }

    /**
     * 抄机械臂：选过取放点的写成一段 create:mechanical_arm（第一格机械臂、后面是取放点，roles 照它记着的取放），
     * 机械臂那一格不写成方块；一个点都没选过的机械臂照抄成方块。
     *
     * @param states 这台机器里机械臂的格与方块状态
     * @param points 一台机械臂记着的取放点
     */
    static MachineCopy arms(Map<BlockPos, BlockState> states, Function<BlockPos, List<CreateStations.ArmPoint>> points) {
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        List<Installation> installations = new ArrayList<>();
        for (BlockPos arm : sorted(states.keySet())) {
            List<CreateStations.ArmPoint> chosen = points.apply(arm);
            if (chosen.isEmpty()) {
                blocks.put(arm, states.get(arm));
                continue;
            }
            List<BlockPos> cells = new ArrayList<>(List.of(arm));
            List<String> roles = new ArrayList<>(List.of(ArmInstallation.ARM));
            for (CreateStations.ArmPoint point : chosen) {
                cells.add(point.pos());
                roles.add(point.mode());
            }
            installations.add(new Installation(ArmInstallation.KIND, cells, roles));
        }
        return new MachineCopy(blocks, installations, List.of(), List.of());
    }

    // 按 y、z、x 排：抄出来的东西每次顺序一样，从下往上读。
    private static List<BlockPos> sorted(Set<BlockPos> cells) {
        return cells.stream().sorted(Comparator.comparingInt((BlockPos pos) -> pos.getY()).thenComparingInt(pos -> pos.getZ())
                .thenComparingInt(pos -> pos.getX())).toList();
    }
}
