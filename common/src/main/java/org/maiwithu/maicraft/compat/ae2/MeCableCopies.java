// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.maiwithu.maicraft.ability.machine.spi.Installation;
import org.maiwithu.maicraft.ability.machine.spi.MachineCopy;
import org.maiwithu.maicraft.ability.machine.spi.PartCell;

/**
 * 读现场时 ME 线缆格怎么抄回机器蓝图，纯函数。线缆不是能直接放的方块，是一格一格接上的：
 * 线缆格不写成方块，同一种线缆连着的写成一段段线缆，每段一格接一格；后面一段的头挨着前面已经接上的线缆
 * 或挂在网上的设备，照着建时每一格都点得到前一格（或设备）朝它的那一面。线缆上各面的部件写进部件。
 * 只装着部件、没有线缆芯的格（部件直接贴在墙上）装不回来：部件只认装在线缆上，写进说明。
 */
final class MeCableCopies {

    private MeCableCopies() {
    }

    /**
     * @param cells   这台机器里的线缆格（全部，读不到的也在里面）
     * @param read    读得到的线缆格的读数
     * @param device  这一格是不是挂在 ME 网上的设备（控制器、驱动器……）：第一段线缆从挨着设备的那一格接起
     */
    static MachineCopy copy(Set<BlockPos> cells, Map<BlockPos, Ae2Grid.GridCell> read, Predicate<BlockPos> device) {
        List<String> notes = new ArrayList<>();
        Map<BlockPos, String> cables = new LinkedHashMap<>();
        List<PartCell> parts = new ArrayList<>();
        for (BlockPos cell : sorted(cells)) {
            Ae2Grid.GridCell here = read.get(cell);
            if (here == null) {
                notes.add(cell.toShortString() + " 读不到 ME 线缆格，没抄");
                continue;
            }
            if (here.cableItem().isEmpty()) {
                if (!here.parts().isEmpty()) {
                    notes.add(cell.toShortString() + " 只装着部件、没有线缆芯，照着建装不回来（部件要装在线缆上）");
                }
                continue;
            }
            cables.put(cell, here.cableItem().get());
            for (Direction side : Direction.values()) {
                Ae2Grid.PartView part = here.parts().get(side);
                if (part != null) parts.add(new PartCell(cell, side, part.itemId()));
            }
        }
        return new MachineCopy(Map.of(), runs(cables, device), parts, notes);
    }

    /**
     * 把线缆格排成一段段：每次从还没排的格里挑一格开头（先挑挨着已排线缆或设备的），沿同一种线缆一直往前接，
     * 能直走就直走，接不下去这一段就到头。按格的顺序挑，结果每次一样。
     */
    static List<Installation> runs(Map<BlockPos, String> cables, Predicate<BlockPos> device) {
        List<BlockPos> order = sorted(cables.keySet());
        Set<BlockPos> laid = new HashSet<>();
        List<Installation> runs = new ArrayList<>();
        while (laid.size() < order.size()) {
            BlockPos start = order.stream().filter(cell -> !laid.contains(cell) && touches(cell, laid, device))
                    .findFirst()
                    .orElseGet(() -> order.stream().filter(cell -> !laid.contains(cell)).findFirst().orElseThrow());
            String kind = cables.get(start);
            List<BlockPos> run = new ArrayList<>(List.of(start));
            laid.add(start);
            Direction heading = null;
            BlockPos at = start;
            while (true) {
                Optional<Direction> next = nextStep(at, heading, kind, cables, laid);
                if (next.isEmpty()) break;
                heading = next.get();
                at = at.relative(heading);
                run.add(at);
                laid.add(at);
            }
            runs.add(new Installation(kind, run));
        }
        return runs;
    }

    // 往前接的下一格：同一种线缆、还没排的；能顺着上一步的方向就顺着，不然按六个方向的固定顺序挑。
    private static Optional<Direction> nextStep(BlockPos at, Direction heading, String kind, Map<BlockPos, String> cables,
            Set<BlockPos> laid) {
        if (heading != null && fits(at.relative(heading), kind, cables, laid)) return Optional.of(heading);
        for (Direction side : Direction.values()) {
            if (fits(at.relative(side), kind, cables, laid)) return Optional.of(side);
        }
        return Optional.empty();
    }

    private static boolean fits(BlockPos cell, String kind, Map<BlockPos, String> cables, Set<BlockPos> laid) {
        return kind.equals(cables.get(cell)) && !laid.contains(cell);
    }

    // 挨着已经排好的线缆，或挨着挂在网上的设备：从这里接，照着建时点得到旁边那一格。
    private static boolean touches(BlockPos cell, Set<BlockPos> laid, Predicate<BlockPos> device) {
        for (Direction side : Direction.values()) {
            BlockPos beside = cell.relative(side);
            if (laid.contains(beside) || device.test(beside)) return true;
        }
        return false;
    }

    // 按 y、z、x 排：每次排出来一样。
    private static List<BlockPos> sorted(Set<BlockPos> cells) {
        return cells.stream().sorted(Comparator.comparingInt((BlockPos pos) -> pos.getY()).thenComparingInt(pos -> pos.getZ())
                .thenComparingInt(pos -> pos.getX())).toList();
    }
}
