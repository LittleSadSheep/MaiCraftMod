// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design.api;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.behavior.permission.ReadsRememberedPlaces;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 建筑档案这一摊要用的东西：档案库、记过的地点（看同名的是不是同一栋）、把一栋记进世界记忆的那一步、设施怎么认。
 * build 与 design 共用一份，进世界时建好。
 *
 * @param archives 建筑档案库
 * @param places   按名字查记过的地点
 * @param remember 档案存好后把"这一栋在哪、叫什么"记进世界记忆
 * @param facility 方块 ID 是不是设施（箱子、工作台、熔炉这类），布局里用
 */
public record BuildingRecords(BuildingArchives archives, ReadsRememberedPlaces places,
                              Consumer<BuildingArchive> remember, Predicate<String> facility) {

    public BuildingRecords {
        Objects.requireNonNull(archives, "archives");
        Objects.requireNonNull(places, "places");
        Objects.requireNonNull(remember, "remember");
        Objects.requireNonNull(facility, "facility");
    }

    /** 按名字找一栋的结论：找到了、没有这一栋、或和一个记过的地点同名却不在一处。 */
    public sealed interface Lookup {
        record Found(BuildingArchive archive) implements Lookup {}

        record Missing() implements Lookup {}

        /** 同名的记过地点不在这一栋的范围里：说不清指的是哪个。 */
        record Ambiguous(WorldPosition place, BuildingArchive archive) implements Lookup {}
    }

    /**
     * 按名字找一栋。有这份档案时再看同名的记过地点：落在这一栋的范围里（例如屋里记的"庇护所"）就是同一栋；
     * 在别处就是两样东西同一个名字，交给调用方问清楚。
     */
    public Lookup lookup(String name) {
        Optional<BuildingArchive> archive = archives.find(name);
        if (archive.isEmpty()) return new Lookup.Missing();
        Optional<WorldPosition> place = places.place(name);
        if (place.isPresent()) {
            WorldPosition at = place.get();
            boolean sameDimension = at.dimension() == null || at.dimension().equals(archive.get().dimension());
            if (!sameDimension || !archive.get().bounds().contains(new BlockPos(at.x(), at.y(), at.z()))) {
                return new Lookup.Ambiguous(at, archive.get());
            }
        }
        return new Lookup.Found(archive.get());
    }
}
