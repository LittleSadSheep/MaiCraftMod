// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.navigation.spi.MovingStructure;
import org.maiwithu.maicraft.behavior.navigation.spi.ObstacleSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 寻路眼里会动的结构：进世界时装上联动登记的障碍来源，每次算路、沿路检查时问一遍，按统一的规矩换成走不得的地方。
 * 只在主线程问；同一刻、关注位置没挪一格时复用上一回的答案（后台算路用的是开算时冻结的那一份）。
 * 正坐着的电梯由这一段登记，坐完撤掉：坐着的轿厢和它的电梯井不挡自己。
 */
public final class NavigationObstacles {
    private static final Logger LOG = LoggerFactory.getLogger(NavigationObstacles.class);

    /** 问多大范围：角色周围水平 64 格、上下 32 格。 */
    static final double HORIZONTAL = 64;
    static final double VERTICAL = 32;

    private static final AtomicReference<List<ObstacleSource>> SOURCES = new AtomicReference<>(List.of());
    private static final Set<String> RIDING = ConcurrentHashMap.newKeySet();
    /** 上一回问的答案：同一个世界、同一刻、关注位置没挪一格时复用。 */
    private static final AtomicReference<Answer> LAST = new AtomicReference<>(null);

    private NavigationObstacles() {}

    /**
     * 这一刻的障碍：给碰撞检查的全部，和其中正在动的那些（挡路时先等它过去）。
     *
     * @param all    全部走不得的地方，含没读全的结构的整体范围
     * @param moving 其中正在动的结构换出来的地方
     */
    public record View(PhysicalObstacleSnapshot all, List<AABB> moving) {
        static final View EMPTY = new View(PhysicalObstacleSnapshot.EMPTY, List.of());

        public View {
            moving = List.copyOf(moving);
        }
    }

    private record Answer(WeakReference<Level> level, long tick, Vec3 focus, View view) {
        boolean reusable(Level asked, Vec3 at) {
            return level.get() == asked && tick == asked.getGameTime() && focus.distanceToSqr(at) < 1;
        }
    }

    /** 进世界时装上联动登记的障碍来源；离开世界时装空表。 */
    public static void install(List<ObstacleSource> sources) {
        SOURCES.set(List.copyOf(sources));
        RIDING.clear();
        LAST.set(null);
    }

    /** 这一段坐上了这个结构（电梯）：它的轿厢与电梯井不挡；坐完调 {@link #leave}。 */
    public static void ride(String structureId) {
        RIDING.add(structureId);
        invalidate();
    }

    public static void leave(String structureId) {
        RIDING.remove(structureId);
        invalidate();
    }

    /** 这一刻在走路的角色附近的障碍；没装任何来源时是空的。 */
    public static View capture(Level level, Player viewer) {
        List<ObstacleSource> sources = SOURCES.get();
        if (sources.isEmpty() || level == null || viewer == null) return View.EMPTY;
        Vec3 focus = viewer.position();
        Answer last = LAST.get();
        if (last != null && last.reusable(level, focus)) return last.view();
        AABB region = new AABB(focus.x - HORIZONTAL, focus.y - VERTICAL, focus.z - HORIZONTAL,
                focus.x + HORIZONTAL, focus.y + VERTICAL, focus.z + HORIZONTAL);
        List<MovingStructure> structures = new ArrayList<>();
        for (ObstacleSource source : sources) {
            try {
                source.obstacles(level, viewer, region, structures::add);
            } catch (RuntimeException failure) {
                // 一个来源读坏了只少它这一份，别的照常；读不到的结构这一刻就看不见，日志写明。
                LOG.warn("障碍来源读不出来，这一刻少它这一份", failure);
            }
        }
        View view = convert(structures, Set.copyOf(RIDING));
        LAST.set(new Answer(new WeakReference<>(level), level.getGameTime(), focus, view));
        return view;
    }

    /** 一串结构换成走不得的地方：全部的给碰撞检查，在动的另列一份。 */
    static View convert(List<MovingStructure> structures, Set<String> riding) {
        if (structures.isEmpty()) return View.EMPTY;
        List<AABB> all = new ArrayList<>();
        List<AABB> moving = new ArrayList<>();
        int conservative = 0;
        for (MovingStructure structure : structures) {
            List<AABB> blocked = ObstacleShapes.blocked(structure, riding);
            all.addAll(blocked);
            if (structure.moving()) moving.addAll(blocked);
            if (!blocked.isEmpty() && ObstacleShapes.collision(structure).size() == 1 && !structure.complete()) conservative++;
        }
        return new View(new PhysicalObstacleSnapshot(all, structures.size(), conservative,
                conservative > 0 ? "partial" : "observed"), moving);
    }

    private static void invalidate() {
        LAST.set(null);
    }
}
