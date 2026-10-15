// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.worldmemory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 一册记忆：一个世界里的全部记忆记录，加上按名字记住的地点（家、床这类）与记住的区域（玩家的地盘）。
 *
 * <p>它是一次修改的最小单位：每次记住或忘记都整册重新编码存盘，避免半新半旧的记忆。
 */
record MemoryBook(List<MemoryRecord> records, Map<String, WorldPosition> places,
        Map<String, RememberedRegion> regions) {

    MemoryBook {
        records = List.copyOf(records);
        places = Map.copyOf(places);
        regions = Map.copyOf(regions);
    }

    static MemoryBook empty() {
        return new MemoryBook(List.of(), Map.of(), Map.of());
    }

    /**
     * 记下或更新一条记忆：说的是同一个东西的已有记录（同一片产地可能早先记了好几条）按先后并成一条，
     * 再并进这次的观察（来源取更强、确认过的内容覆盖），放回册子；别的记录不动。
     */
    MemoryBook withRecord(MemoryRecord incoming) {
        List<MemoryRecord> next = new ArrayList<>();
        MemoryRecord earlier = null;
        for (MemoryRecord existing : records) {
            if (existing.sameThing(incoming)) {
                earlier = earlier == null ? existing : earlier.mergedWith(existing);
            } else {
                next.add(existing);
            }
        }
        next.add(earlier == null ? incoming : earlier.mergedWith(incoming));
        return new MemoryBook(next, places, regions);
    }

    /**
     * 把册子里说的是同一个东西的记录并成一条：同一片产地按片认之前存下的记忆里，
     * 同一片树林可能记了上百条，读进来时收拢一次，之后的查询和存盘都只看并好的。没有可并的原册返回。
     */
    MemoryBook withDuplicatesMerged() {
        MemoryBook merged = new MemoryBook(List.of(), places, regions);
        for (MemoryRecord record : records) {
            merged = merged.withRecord(record);
        }
        return merged.records().size() == records.size() ? this : merged;
    }

    /** 记住一个按名字叫的地点；同名地点用新位置覆盖旧位置。 */
    MemoryBook withPlace(String name, WorldPosition position) {
        var next = new HashMap<>(places);
        next.put(name, position);
        return new MemoryBook(records, next, regions);
    }

    /** 记住一块区域；同名区域用新范围覆盖旧范围。 */
    MemoryBook withRegion(RememberedRegion region) {
        var next = new HashMap<>(regions);
        next.put(region.name(), region);
        return new MemoryBook(records, places, next);
    }

    /** 忘掉一条记忆：那个东西搬走了、不见了，留着只会误导。其他记忆不动。 */
    MemoryBook without(MemoryKind kind, WorldPosition position) {
        List<MemoryRecord> next = records.stream()
                .filter(record -> record.kind() != kind || !MemoryRecord.sameSpot(record.position(), position))
                .toList();
        return new MemoryBook(next, places, regions);
    }

    /** 忘掉一块区域：地界变了或记错了，留着只会把能干活的格子一直锁住。其他记忆不动。 */
    MemoryBook withoutRegion(String name) {
        var next = new HashMap<>(regions);
        next.remove(name);
        return new MemoryBook(records, places, next);
    }

    /** 忘掉一个按名字记的地点：名字没记过时原册返回，别的记忆不动。 */
    MemoryBook withoutPlace(String name) {
        if (!places.containsKey(name)) return this;
        var next = new HashMap<>(places);
        next.remove(name);
        return new MemoryBook(records, next, regions);
    }
}
