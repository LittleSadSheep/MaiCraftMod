// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 开打后还在追我的：这一仗里真追过我的那只（看得见我又亮着攻击标记、十秒内打过我、点着引信），之后哪一刻攻击标记暗了
 * 也照样算追兵——只要它还朝我来（往我这边挪，或贴到快够得着我）；离开、站着不动或看不见满三秒才放下，
 * 死了、走出视野当场放下。点名战斗与自卫用同一份。
 *
 * <p>原版僵尸找不到路时攻击标记一秒一闪：实机 10-15 自卫对十二三格外的僵尸"逼近→打完了"一秒一切，
 * 它一会儿算追兵一会儿不算，背包开了又关，换到一半的斧子被收掉，最后空手挨了六下。
 */
final class StillChasing {

    /** 离开、站着不动或看不见满这么多刻（三秒）才不算追兵。 */
    static final int LET_GO_TICKS = 60;
    /** 先前追过的走到视野半径外这么多格内照样看着：在视野边上进进出出的不算走了。 */
    static final double EDGE_MARGIN = 4.0;
    /** 一刻里往我这边挪这么多（格）以上才算朝我来：原地晃悠的不算。 */
    static final double CLOSING_STEP = 0.02;

    /** 这一刻看到的一只：场上的样子，和它此刻是不是真在追（不算先前追过）。 */
    record Sighting(Battlefield.Foe foe, boolean chasingNow) {}

    /** 追过我的那些：实体编号到上一刻在哪、连着安静了几刻。 */
    private final Map<Integer, Track> tracks = new HashMap<>();

    private static final class Track {
        double x;
        double z;
        int quiet;
    }

    /** 这只先前追过我、还没放下：这一刻攻击标记暗了也照样算追兵。 */
    boolean holds(int entityId) {
        return tracks.containsKey(entityId);
    }

    /** 有没有还没放下的：有就把视野往外多看几格，看它是走了还是在边上晃。 */
    boolean any() {
        return !tracks.isEmpty();
    }

    /**
     * 每刻看一遍：此刻真在追的记上、安静刻数清零；先前追过的这一刻没在追，朝我来也清零，离开、站着不动或看不见就安静一刻，
     * 满三秒放下；这一刻不在场上的（死了、走出视野）当场放下。
     *
     * @param meX 我此刻的位置：它挪没挪近只按它自己的挪动算，不把我走过去算成它朝我来
     */
    void observe(List<Sighting> sightings, double meX, double meZ) {
        Set<Integer> present = new HashSet<>();
        for (Sighting sighting : sightings) {
            Battlefield.Foe foe = sighting.foe();
            Track track = tracks.get(foe.id());
            if (track == null) {
                if (!sighting.chasingNow()) continue;
                track = new Track();
                tracks.put(foe.id(), track);
            } else if (sighting.chasingNow() || comingAt(track, foe, meX, meZ)) {
                track.quiet = 0;
            } else {
                track.quiet++;
            }
            track.x = foe.threat().x();
            track.z = foe.threat().z();
            present.add(foe.id());
        }
        tracks.keySet().retainAll(present);
        tracks.values().removeIf(track -> track.quiet >= LET_GO_TICKS);
    }

    // 还朝我来：看得见，且贴到快够得着我了，或这一刻它自己往我这边挪了一点。
    private static boolean comingAt(Track before, Battlefield.Foe foe, double meX, double meZ) {
        if (!foe.threat().visible()) return false;
        if (foe.imminent()) return true;
        double was = Math.hypot(before.x - meX, before.z - meZ);
        double now = Math.hypot(foe.threat().x() - meX, foe.threat().z() - meZ);
        return was - now >= CLOSING_STEP;
    }
}
