// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.machine.spi.MachineType;

/**
 * 机器分组：把认领了的机器方块分成一台台，纯函数。
 *
 * <p>没有档案边界时（机器档案随施工接入，现在还没有），一台机器就是现场看到的一块：
 * 相邻、并且属于同一张网络（或都不在网络里）的机器方块连成一台。压机和它下面的置物台
 * 都不在网络里又相邻，算一台；隔着线缆连到同一张 ME 网络的两个方块相邻也算一台，
 * 各在不同网络上的相邻方块不算。边界判断写在这里，机器查看与操作共用这一份。
 */
final class MachineGrouping {

    /** 一格被认领的机器方块：位置与认领它的机器类型。 */
    record Claimed(BlockPos pos, MachineType type) {
    }

    /** 分出来的一台机器：编号从 1 起（结果里叫 m1、m2），格、类型与挂着的网络都在。 */
    record Machine(int number, List<BlockPos> cells, List<MachineType> types, Set<String> networkIds) {
    }

    private MachineGrouping() {
    }

    /**
     * 分组：相邻（六向贴面）且同属一张网的机器方块连成一台。
     *
     * @param claimed     这一轮认领的全部机器方块
     * @param membershipOf 每格挂着的网络（网络种类 → 网络编号）；不在任何网上给空表
     */
    static List<Machine> group(List<Claimed> claimed, Function<BlockPos, Map<String, String>> membershipOf) {
        Map<BlockPos, Integer> indexOf = new LinkedHashMap<>();
        for (Claimed cell : claimed) {
            indexOf.put(cell.pos(), indexOf.size());
        }
        // 并查集：相邻且同网的格并到一起；都不在网络里也并（压机与置物台是一台机器的两部分）。
        int[] parent = new int[claimed.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < claimed.size(); i++) {
            BlockPos here = claimed.get(i).pos();
            for (BlockPos neighbor : List.of(here.above(), here.below(), here.north(),
                    here.south(), here.east(), here.west())) {
                Integer other = indexOf.get(neighbor);
                if (other != null && sameNetwork(membershipOf.apply(here), membershipOf.apply(neighbor))) {
                    parent[find(parent, i)] = find(parent, other);
                }
            }
        }
        Map<Integer, List<Claimed>> merged = new LinkedHashMap<>();
        for (int i = 0; i < claimed.size(); i++) {
            merged.computeIfAbsent(find(parent, i), key -> new ArrayList<>()).add(claimed.get(i));
        }
        // 按位置从低到高、先西后北排序编号：同一片机器每次看，编号都一样。
        List<List<Claimed>> groups = new ArrayList<>(merged.values());
        groups.sort(Comparator.comparingInt((List<Claimed> group) -> group.get(0).pos().getY())
                .thenComparingInt(group -> group.get(0).pos().getX())
                .thenComparingInt(group -> group.get(0).pos().getZ()));
        List<Machine> machines = new ArrayList<>();
        for (int number = 0; number < groups.size(); number++) {
            List<Claimed> group = groups.get(number);
            List<BlockPos> cells = group.stream().map(Claimed::pos).toList();
            List<MachineType> types = group.stream().map(Claimed::type).distinct().toList();
            Set<String> networks = new LinkedHashSet<>();
            for (Claimed cell : group) {
                networks.addAll(membershipOf.apply(cell.pos()).values());
            }
            machines.add(new Machine(number + 1, cells, types, networks));
        }
        return List.copyOf(machines);
    }

    /** 两格算不算同一张网：都有同一种类的网且编号一样，或都不在任何网上。 */
    private static boolean sameNetwork(Map<String, String> left, Map<String, String> right) {
        if (left.isEmpty() && right.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, String> entry : left.entrySet()) {
            if (right.containsKey(entry.getKey()) && right.get(entry.getKey()).equals(entry.getValue())) {
                return true;
            }
        }
        return false;
    }

    private static int find(int[] parent, int at) {
        while (parent[at] != at) {
            parent[at] = parent[parent[at]];
            at = parent[at];
        }
        return at;
    }

    /** 一格挂着的网络：问遍登记的网络读取器，每张网给"种类 → 网络编号"；不属于任何网给空表。 */
    static Map<String, String> membershipsOf(MachineServices services, BlockPos at) {
        Map<String, String> out = new LinkedHashMap<>();
        for (var reader : services.networkReaders()) {
            Optional<String> id = reader.membership(at);
            if (id.isPresent()) {
                out.put(reader.kind().id(), id.get());
            }
        }
        return out;
    }
}
