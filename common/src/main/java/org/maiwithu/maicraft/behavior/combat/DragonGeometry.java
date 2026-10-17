// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 打龙的几何：水晶的爆炸范围、龙息危险区、龙冲撞与火球的航线、砍龙头的站位、射水晶的站位、等龙落与回血的落点。
 * 纯函数，不碰游戏；落点能不能站由调用方给的地表（{@link Surface}）回答。
 *
 * <p>数都来自原版：水晶威力 6，伤害波及两倍远；龙趴着喷的龙息云落在龙头前 2.5 格、半径 5；火球落地的云从 3 涨到 7；
 * 翅膀碰撞箱在身子两侧 4.5 格、外扩 4 格推人；龙头、龙脖子外扩 1 格碰到就挨 10 点。
 */
public final class DragonGeometry {

    /** 水晶爆炸的伤害波及多远（格）：威力 6 的两倍。 */
    public static final double CRYSTAL_BLAST = CombatGeometry.blastReach(6.0f);
    /** 射水晶时离它至少这么远：爆炸波及再留一格。 */
    public static final double CRYSTAL_CLEARANCE = CRYSTAL_BLAST + 1.0;
    /** 射水晶最远多远（格）：柱子最高四十多格，站在十几格外仰着射要这么远，弓拉满射得到。 */
    public static final double CRYSTAL_SHOT_RANGE = 48.0;
    /** 射飞着的龙最远多远（格）：与普通射箭一样，再远箭有下坠、龙又飞得快，基本射不中。 */
    public static final double DRAGON_SHOT_RANGE = 32.0;
    /** 主岛的水晶都在出口门这么远内（格）：外岛的不归这一仗。 */
    public static final double ISLAND_RADIUS = 160.0;

    /** 站进龙息云的判定，水平多留这么多（一刻能走的距离）。 */
    public static final double CLOUD_MARGIN = 1.0;
    /** 云那一层有多厚：原版药水云的碰撞箱只有半格高。 */
    static final double CLOUD_LAYER = 0.5;
    /** 上下各多留这么多：落地的晃动、台阶。 */
    static final double CLOUD_VERTICAL_MARGIN = 0.5;
    /** 还在涨的云一秒涨多少：火球的云三十秒从 3 涨到 7。 */
    static final double CLOUD_GROWTH = 0.15;

    /** 玩家身高、眼睛高度、半个身宽。 */
    static final double PLAYER_HEIGHT = 1.8;
    public static final double EYE_HEIGHT = 1.62;
    static final double HALF_WIDTH = Battlefield.PLAYER_HALF_WIDTH;

    /** 龙扫过来时翅膀够得着多宽（水平，格）：翅膀在身子两侧 4.5 格、外扩 4 格推人，再留一格半。 */
    public static final double SWEEP_WIDTH = 12.0;
    /** 高度差在这么多以内才会被扫到。 */
    static final double SWEEP_HEIGHT = 8.0;
    /** 龙的航线往前推多少刻。 */
    static final int SWEEP_PATH_TICKS = 60;
    /** 离最近处还有这么多刻以内才躲：再远它多半会转弯。 */
    public static final int SWEEP_WARNING_TICKS = 40;
    /** 离撞上不到这么多刻：跑不开了，面朝它举盾。 */
    public static final int BRACE_TICKS = 10;
    /** 龙火球的落点离我这么近（格）就躲：落地时 4 格内的活物会被云直接罩住，再留一格。 */
    public static final double FIREBALL_MISS = 5.0;
    /** 龙火球往前推多少刻。 */
    static final int FIREBALL_TICKS = 40;

    /** 龙趴着喷的龙息云落在龙头正前方多远、半径多大。 */
    static final double BREATH_AHEAD = 2.5;
    static final double BREATH_RADIUS = 5.0;
    /** 吼到第几刻就先退：吼两秒（40 刻）后紧接着喷，云在喷的第 10 刻冒出来，退出那一片要提前半秒。 */
    public static final int BREATH_WARNING_TICKS = 30;
    /** 龙头、龙脖子碰撞箱外扩多少碰到就挨撞，再留多少余量。 */
    static final double STRIKE_ZONE = 1.0;
    static final double STRIKE_ZONE_MARGIN = 0.2;
    /** 砍龙头时在龙头周围这么多格里找站位。 */
    static final int HEAD_SEARCH = 4;

    /** 等龙落的一圈离门心多远：龙趴下后只对 20 格内的人喷，20 格外的五秒后直接冲过来。 */
    static final int WAIT_MIN = 8;
    static final int WAIT_MAX = 14;
    /** 回血的一圈离门心多远：龙趴着时喷不到，也不在它落地的路上。 */
    static final int REST_MIN = 20;
    static final int REST_MAX = 30;
    /** 龙倒下后收经验的一圈离门心多远：门洞外。 */
    static final int COLLECT_MIN = 4;
    static final int COLLECT_MAX = 6;
    /** 射水晶的一圈离柱子多远（水平）。 */
    static final int STANCE_MIN = 10;
    static final int STANCE_MAX = 30;
    /** 一圈候选的半径步长（格）与角度步长（度）。 */
    static final int RING_STEP = 2;
    static final double RING_DEGREES = 15.0;
    /** 落点离岛边至少多远（格）：射水晶、等龙落、收经验 3 格，回血 6 格（站着不动更怕被扇下去）。 */
    static final double EDGE = 3.0;
    static final double REST_EDGE = 6.0;
    /** 出口门门洞的半径：门亮了以后不站进去（走进去就回主世界了）。 */
    static final double PORTAL_HOLE = 3.5;

    private DragonGeometry() {}

    /** 地表：这一列站得住的脚的高度（已加载、脚下踩得住、不伤人、不是虚空）；站不住为空。 */
    @FunctionalInterface
    public interface Surface {
        OptionalInt feetY(int x, int z);
    }

    /**
     * 一条航线离我最近的那一处。
     *
     * @param miss  最近时离我多远（格）
     * @param ticks 再过几刻到最近处
     */
    public record Pass(double miss, int ticks, double x, double y, double z) {}

    /** 龙的航线离我（脚下）最近的那一处：只看水平，往前推 {@link #SWEEP_PATH_TICKS} 刻。 */
    public static Pass sweep(DragonArena.Dragon dragon, double x, double y, double z) {
        double vv = dragon.vx() * dragon.vx() + dragon.vz() * dragon.vz();
        double t = vv < 1.0e-9 ? 0 : clamp(-((dragon.x() - x) * dragon.vx() + (dragon.z() - z) * dragon.vz()) / vv,
                0, SWEEP_PATH_TICKS);
        double cx = dragon.x() + dragon.vx() * t;
        double cz = dragon.z() + dragon.vz() * t;
        return new Pass(Math.hypot(cx - x, cz - z), (int) Math.round(t), cx, dragon.y() + dragon.vy() * t, cz);
    }

    /** 这一趟会不会扫到我：龙在飞、最近处水平 12 格内、高度差 8 格内、40 刻内就到。 */
    public static boolean sweepsMe(DragonArena.Dragon dragon, Pass pass, double y) {
        return dragon.activity().airborne() && pass.miss() < SWEEP_WIDTH && pass.ticks() <= SWEEP_WARNING_TICKS
                && Math.abs(pass.y() - y) < SWEEP_HEIGHT;
    }

    /** 龙火球的航线离我身子中间最近的那一处（三维），往前推 {@link #FIREBALL_TICKS} 刻。 */
    public static Pass fireball(DragonArena.Fireball ball, double x, double y, double z) {
        double cy = y + PLAYER_HEIGHT / 2;
        double dx = ball.x() - x;
        double dy = ball.y() - cy;
        double dz = ball.z() - z;
        double vv = ball.vx() * ball.vx() + ball.vy() * ball.vy() + ball.vz() * ball.vz();
        double t = vv < 1.0e-9 ? 0 : clamp(-(dx * ball.vx() + dy * ball.vy() + dz * ball.vz()) / vv, 0, FIREBALL_TICKS);
        double px = ball.x() + ball.vx() * t;
        double py = ball.y() + ball.vy() * t;
        double pz = ball.z() + ball.vz() * t;
        double miss = Math.sqrt((px - x) * (px - x) + (py - cy) * (py - cy) + (pz - z) * (pz - z));
        return new Pass(miss, (int) Math.round(t), px, py, pz);
    }

    /** 这个火球会不会落到我身边。 */
    public static boolean fireballHits(Pass pass) {
        return pass.miss() < FIREBALL_MISS;
    }

    /** 走位时要离这团云多远（水平）：半径、一秒的涨幅与余量。 */
    public static double cloudReach(DragonArena.Cloud cloud) {
        return cloud.radius() + CLOUD_GROWTH + CLOUD_MARGIN;
    }

    /**
     * 脚站在这里算不算进了这团云：原版按脚下中心离云心的水平距离与半径比，且身子和云那一层（半格厚）有重叠；
     * 水平多留一格、上下各多留半格。不知道落在哪一层的云（还没冒出来）哪一层都算。
     */
    public static boolean inCloud(DragonArena.Cloud cloud, double x, double y, double z) {
        if (Math.hypot(x - cloud.x(), z - cloud.z()) > cloudReach(cloud)) return false;
        if (Double.isNaN(cloud.y())) return true;
        return y < cloud.y() + CLOUD_LAYER + CLOUD_VERTICAL_MARGIN
                && y + PLAYER_HEIGHT > cloud.y() - CLOUD_VERTICAL_MARGIN;
    }

    public static boolean inAnyCloud(List<DragonArena.Cloud> clouds, double x, double y, double z) {
        for (DragonArena.Cloud cloud : clouds) {
            if (inCloud(cloud, x, y, z)) return true;
        }
        return false;
    }

    /** 龙要喷了或正在喷：吼到第 30 刻，或已经在喷。 */
    public static boolean breathComing(DragonArena.Dragon dragon) {
        return dragon.activity() == DragonArena.Activity.ROARING && dragon.activityTicks() >= BREATH_WARNING_TICKS
                || dragon.activity() == DragonArena.Activity.BREATHING;
    }

    /** 按龙头此刻的朝向（身子指向龙头）预先算出这一口龙息云：龙头正前方 2.5 格、半径 5，不知道落在哪一层。 */
    public static DragonArena.Cloud predictedBreath(DragonArena.Dragon dragon) {
        Vec3 head = dragon.head().getCenter();
        double fx = head.x - dragon.x();
        double fz = head.z - dragon.z();
        double length = Math.hypot(fx, fz);
        if (length < 1.0e-6) {
            fx = 0;
            fz = 1;
            length = 1;
        }
        return new DragonArena.Cloud(head.x + fx / length * BREATH_AHEAD, Double.NaN,
                head.z + fz / length * BREATH_AHEAD, BREATH_RADIUS);
    }

    /** 这一刻要躲的龙息：已经有的云，加上龙要喷了时预先算出的那一口。 */
    public static List<DragonArena.Cloud> dangerClouds(DragonArena arena) {
        List<DragonArena.Cloud> clouds = new ArrayList<>(arena.clouds());
        if (arena.dragon() != null && breathComing(arena.dragon())) clouds.add(predictedBreath(arena.dragon()));
        return clouds;
    }

    /** 站在这里（脚下）会不会挨这颗水晶的爆炸：身子中间离它不到爆炸波及再加一格。 */
    public static boolean inBlast(DragonArena.Crystal crystal, double x, double y, double z) {
        return distance(x, y + PLAYER_HEIGHT / 2, z, crystal.x(), crystal.y() + 1.0, crystal.z()) < CRYSTAL_CLEARANCE;
    }

    /** 站在这里（脚下）够不够得着龙头：眼睛到龙头碰撞箱不超过交互距离。 */
    public static boolean reachesHead(double x, double y, double z, AABB head, double reach) {
        return CombatGeometry.playerReaches(new Vec3(x, y + EYE_HEIGHT, z), head, reach);
    }

    /** 站在这里（脚下）身子会不会碰到龙头、龙脖子外扩一格的那一圈（原版碰到就挨 10 点）。 */
    public static boolean inStrikeZone(double x, double y, double z, AABB head, AABB neck) {
        AABB me = new AABB(x - HALF_WIDTH, y, z - HALF_WIDTH, x + HALF_WIDTH, y + PLAYER_HEIGHT, z + HALF_WIDTH);
        double grow = STRIKE_ZONE + STRIKE_ZONE_MARGIN;
        return head.inflate(grow).intersects(me) || neck.inflate(grow).intersects(me);
    }

    /** 走位时离龙头、龙脖子多远（水平，从碰撞箱中心算）才不挨撞：碰撞箱半宽、外扩、余量与我半个身宽。 */
    public static double strikeZoneReach(AABB part) {
        return part.getXsize() / 2 + STRIKE_ZONE + STRIKE_ZONE_MARGIN + HALF_WIDTH;
    }

    /**
     * 砍龙头的站位：龙头周围四格内、站得住、够得着龙头、不碰龙头龙脖子、不在龙息里、门亮了不进门洞；离我近的在前。
     */
    public static List<BlockPos> headStances(DragonArena.Dragon dragon, DragonArena.Portal portal,
                                             List<DragonArena.Cloud> clouds, double reach, Surface surface,
                                             double x, double z) {
        Vec3 head = dragon.head().getCenter();
        int hx = (int) Math.floor(head.x);
        int hz = (int) Math.floor(head.z);
        List<BlockPos> stances = new ArrayList<>();
        for (int dx = -HEAD_SEARCH; dx <= HEAD_SEARCH; dx++) {
            for (int dz = -HEAD_SEARCH; dz <= HEAD_SEARCH; dz++) {
                OptionalInt feet = surface.feetY(hx + dx, hz + dz);
                if (feet.isEmpty()) continue;
                BlockPos cell = new BlockPos(hx + dx, feet.getAsInt(), hz + dz);
                double cx = cell.getX() + 0.5;
                double cz = cell.getZ() + 0.5;
                if (!reachesHead(cx, cell.getY(), cz, dragon.head(), reach)
                        || inStrikeZone(cx, cell.getY(), cz, dragon.head(), dragon.neck())
                        || inAnyCloud(clouds, cx, cell.getY(), cz)
                        || inPortalHole(portal, cx, cz)) {
                    continue;
                }
                stances.add(cell);
            }
        }
        stances.sort(nearest(x, z));
        return stances;
    }

    /** 射这颗水晶的站位：离柱子 10～30 格一圈、离水晶 13～48 格、不在龙息里、离岛边至少 3 格；离我近的在前。 */
    public static List<BlockPos> crystalStances(DragonArena.Crystal crystal, List<DragonArena.Cloud> clouds,
                                                Surface surface, double x, double z) {
        return ring(crystal.x(), crystal.z(), STANCE_MIN, STANCE_MAX, surface, x, z, cell -> {
            double cx = cell.getX() + 0.5;
            double cz = cell.getZ() + 0.5;
            double eye = distance(cx, cell.getY() + EYE_HEIGHT, cz, crystal.x(), crystal.y() + 1.0, crystal.z());
            return eye >= CRYSTAL_CLEARANCE && eye <= CRYSTAL_SHOT_RANGE
                    && !inAnyCloud(clouds, cx, cell.getY(), cz) && edgeSafe(surface, cell.getX(), cell.getZ(), EDGE);
        });
    }

    /** 等龙落的落点：离门心 8～14 格一圈、不在龙息里、门亮了不进门洞、离岛边至少 3 格；离我近的在前。 */
    public static List<BlockPos> waitSpots(DragonArena.Portal portal, List<DragonArena.Cloud> clouds,
                                           Surface surface, double x, double z) {
        return ring(portal.x(), portal.z(), WAIT_MIN, WAIT_MAX, surface, x, z,
                cell -> openGround(cell, portal, clouds, surface, EDGE));
    }

    /** 回血的落点：离门心 20～30 格一圈、不在龙息里、不在还没打掉的水晶的爆炸范围里、离岛边至少 6 格。 */
    public static List<BlockPos> restSpots(DragonArena.Portal portal, List<DragonArena.Crystal> crystals,
                                           List<DragonArena.Cloud> clouds, Surface surface, double x, double z) {
        return ring(portal.x(), portal.z(), REST_MIN, REST_MAX, surface, x, z, cell -> {
            if (!openGround(cell, portal, clouds, surface, REST_EDGE)) return false;
            for (DragonArena.Crystal crystal : crystals) {
                if (inBlast(crystal, cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5)) return false;
            }
            return true;
        });
    }

    /** 龙倒下后收经验的落点：离门心 4～6 格、门洞外、不在龙息里。 */
    public static List<BlockPos> collectSpots(DragonArena.Portal portal, List<DragonArena.Cloud> clouds,
                                              Surface surface, double x, double z) {
        return ring(portal.x(), portal.z(), COLLECT_MIN, COLLECT_MAX, surface, x, z,
                cell -> openGround(cell, portal, clouds, surface, EDGE));
    }

    /** 门亮了以后，这一点在不在门洞里。 */
    public static boolean inPortalHole(DragonArena.Portal portal, double x, double z) {
        return portal != null && portal.lit() && Math.hypot(x - portal.x(), z - portal.z()) < PORTAL_HOLE;
    }

    /**
     * 离岛边够不够远：往八个方向各走 margin 格都还有站得住的地面。主岛边上被翅膀一扇就掉进虚空。
     */
    public static boolean edgeSafe(Surface surface, int x, int z, double margin) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                double scale = dx != 0 && dz != 0 ? margin / Math.sqrt(2) : margin;
                if (surface.feetY(x + (int) Math.round(dx * scale), z + (int) Math.round(dz * scale)).isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    // 空地：不在龙息里、门亮了不进门洞、离岛边够远。
    private static boolean openGround(BlockPos cell, DragonArena.Portal portal, List<DragonArena.Cloud> clouds,
                                      Surface surface, double edge) {
        double cx = cell.getX() + 0.5;
        double cz = cell.getZ() + 0.5;
        return !inAnyCloud(clouds, cx, cell.getY(), cz) && !inPortalHole(portal, cx, cz)
                && edgeSafe(surface, cell.getX(), cell.getZ(), edge);
    }

    // 以 (cx, cz) 为心、半径 min 到 max 的一圈候选：每 2 格一层、每 15° 一个，取地表站得住的格，再按条件筛、离我近的在前。
    private static List<BlockPos> ring(double cx, double cz, int min, int max, Surface surface, double x, double z,
                                       Predicate<BlockPos> keep) {
        Set<BlockPos> cells = new LinkedHashSet<>();
        for (int radius = min; radius <= max; radius += RING_STEP) {
            for (double degrees = 0; degrees < 360; degrees += RING_DEGREES) {
                double radians = Math.toRadians(degrees);
                int cellX = (int) Math.floor(cx + radius * Math.cos(radians));
                int cellZ = (int) Math.floor(cz + radius * Math.sin(radians));
                OptionalInt feet = surface.feetY(cellX, cellZ);
                if (feet.isPresent()) cells.add(new BlockPos(cellX, feet.getAsInt(), cellZ));
            }
        }
        List<BlockPos> kept = new ArrayList<>();
        for (BlockPos cell : cells) {
            if (keep.test(cell)) kept.add(cell);
        }
        kept.sort(nearest(x, z));
        return kept;
    }

    private static Comparator<BlockPos> nearest(double x, double z) {
        return Comparator.comparingDouble((BlockPos cell) -> Math.hypot(cell.getX() + 0.5 - x, cell.getZ() + 0.5 - z))
                .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ);
    }

    static double distance(double ax, double ay, double az, double bx, double by, double bz) {
        return Math.sqrt((ax - bx) * (ax - bx) + (ay - by) * (ay - by) + (az - bz) * (az - bz));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
