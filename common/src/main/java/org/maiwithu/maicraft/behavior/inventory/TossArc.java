// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.game.player.PlayerContext;
/**
 * 原版丢东西的抛物线，纯计算：Q 丢出的东西从眼睛下方 0.3 格出手，沿视线以 0.3 的速度飞出、再往上加 0.1；
 * 竖直方向随机多出 ±0.1，水平方向随机偏 0.02 以内；之后每刻先受重力 0.04、再移动、再乘 0.98 的阻力。
 *
 * <p>往机器顶上扔料前用它挑俯仰角：随机偏差的极端组合都要落进那一格、半路不撞别的方块才算扔得准，
 * 不靠"多半落得进去"碰运气。随机偏差不删、不缩来迁就场地：落不全就是站得不对，换站位或贴近再算。
 * 世界里的碰撞外形从调用方给的读法问；那一格自己不算障碍，东西本来就要落进它的外形里。
 */
public final class TossArc {

    /** 出手点在眼睛下方这么多格。类别：游戏事实（原版 Player.drop）。 */
    static final double RELEASE_DROP = 0.3;
    /** 沿视线飞出的速度与额外往上的速度。类别：游戏事实。 */
    private static final double THROW_SPEED = 0.3;
    private static final double LIFT = 0.1;
    /** 竖直方向的随机速度最多差这么多（两个随机数相减再乘 0.1）。类别：游戏事实。 */
    private static final double VERTICAL_JITTER = 0.1;
    /** 水平方向的随机速度在半径这么大的圆里。类别：游戏事实。 */
    private static final double SIDE_JITTER = 0.02;
    /** 掉落物每刻的重力与阻力。类别：游戏事实（ItemEntity）。 */
    private static final double GRAVITY = 0.04;
    private static final double DRAG = 0.98;
    /** 掉落物的碰撞箱：0.25 见方。类别：游戏事实。 */
    private static final double ITEM_HALF_WIDTH = 0.125;
    private static final double ITEM_HEIGHT = 0.25;
    /** 落点离那一格的边至少留这么多（格）：正压在边线上，滚一下就出去了。 */
    private static final double EDGE_MARGIN = 0.03;
    /** 最多算这么多刻：再久还没落下来，多半是扔到半空里去了。 */
    private static final int MAX_TICKS = 80;
    /** 俯仰角从抬头 45° 扫到几乎垂直往下，每度试一次。 */
    private static final int HIGHEST_PITCH = -45;
    private static final int LOWEST_PITCH = 89;

    private TossArc() {
    }

    /** 世界里一格的碰撞框（世界坐标）；格子没加载时为 null，当作挡住。 */
    @FunctionalInterface
    public interface Obstacles {
        List<AABB> at(BlockPos cell);
    }

    /**
     * 往哪扔：落进哪一格，东西落到离这一格底面多高就被接住。
     * 工作盆的盆沿在 13/16 格，石磨外圈的台面在 6/16 格，朝上的锯面在 12/16 格，粉碎轮中间那一格从格顶就开始卷。
     */
    /**
     * 扔到一格上时东西在多高被接住：那一格方块碰撞外形的顶（传送带、工作盆、置物台的台面），空着的格按落到底（0）。
     * 读不到现场按 0。
     */
    public static double catchHeightOf(PlayerContext context, BlockPos cell) {
        if (context == null || context.level() == null || !context.level().isLoaded(cell)) return 0.0;
        var state = context.level().getBlockState(cell);
        var shape = state.getCollisionShape(context.level(), cell);
        if (shape.isEmpty()) return 0.0;
        return Math.max(0.0, Math.min(1.0, shape.max(Direction.Axis.Y)));
    }

    public record Target(BlockPos cell, double catchHeight) {

        public Target {
            Objects.requireNonNull(cell, "cell");
            if (!(catchHeight >= 0.0 && catchHeight <= 1.0)) {
                throw new IllegalArgumentException("接住东西的高度要在这一格里：" + catchHeight);
            }
            cell = cell.immutable();
        }

        double plane() {
            return cell.getY() + catchHeight;
        }

        Vec3 center() {
            return new Vec3(cell.getX() + 0.5, plane(), cell.getZ() + 0.5);
        }
    }

    /**
     * 挑好的出手方向（原版的偏航、俯仰角，单位度）与最坏情况下落点离那一格中心多远。
     */
    public record Aim(float yaw, float pitch, double worstMiss) {

        /** 镜头要看向的那一点：从眼睛沿出手方向往前四格。 */
        public Vec3 lookPoint(Vec3 eye) {
            return eye.add(direction(yaw, pitch).scale(4.0));
        }
    }

    /**
     * 从这只眼睛往目标那一格扔：朝格子中心转过去，逐度试俯仰角，挑随机偏差的极端组合都落进那一格、
     * 且落点散得最小的那个角度；一个都不行时为 empty，站位不对。
     */
    public static Optional<Aim> choose(Vec3 eye, Target target, Obstacles obstacles) {
        Vec3 toward = target.center().subtract(eye);
        float yaw = (float) (Mth.atan2(-toward.x, toward.z) * Mth.RAD_TO_DEG);
        Aim best = null;
        for (int pitch = HIGHEST_PITCH; pitch <= LOWEST_PITCH; pitch++) {
            double worst = worstMiss(eye, direction(yaw, pitch), target, obstacles);
            if (worst >= 0 && (best == null || worst < best.worstMiss())) {
                best = new Aim(yaw, pitch, worst);
            }
        }
        return Optional.ofNullable(best);
    }

    /** 按此刻实际的视线丢出去，随机偏差的极端组合是不是都落进那一格：转头有容差，出手前用真朝向再核一遍。 */
    public static boolean lands(Vec3 eye, Vec3 view, Target target, Obstacles obstacles) {
        return worstMiss(eye, view, target, obstacles) >= 0;
    }

    // 九个水平偏差（不偏与圆周上八个方向）乘三个竖直偏差，逐个算落点：有一个落不进那一格就是 -1，
    // 都落进去时返回离格子中心最远的那个距离。
    private static double worstMiss(Vec3 eye, Vec3 view, Target target, Obstacles obstacles) {
        Vec3 release = eye.subtract(0, RELEASE_DROP, 0);
        Vec3 nominal = view.normalize().scale(THROW_SPEED).add(0, LIFT, 0);
        double worst = 0;
        for (int side = -1; side < 8; side++) {
            double angle = side * Math.PI / 4;
            double sideX = side < 0 ? 0 : Math.cos(angle) * SIDE_JITTER;
            double sideZ = side < 0 ? 0 : Math.sin(angle) * SIDE_JITTER;
            for (double vertical : new double[] {-VERTICAL_JITTER, 0, VERTICAL_JITTER}) {
                Vec3 landing = landing(release, nominal.add(sideX, vertical, sideZ), target, obstacles);
                if (landing == null) return -1;
                worst = Math.max(worst, Math.hypot(landing.x - target.center().x, landing.z - target.center().z));
            }
        }
        return worst;
    }

    // 一刻一刻往前推：先加重力、再走一步、再乘阻力；落到接住的高度时看落点在不在那一格里，
    // 半路掉落物的碰撞箱碰到别的方块就算没扔准。
    private static Vec3 landing(Vec3 start, Vec3 velocity, Target target, Obstacles obstacles) {
        Vec3 at = start;
        Vec3 speed = velocity;
        double plane = target.plane();
        if (at.y <= plane) return null;
        for (int tick = 0; tick < MAX_TICKS; tick++) {
            speed = speed.add(0, -GRAVITY, 0);
            Vec3 next = at.add(speed);
            if (next.y <= plane) {
                Vec3 landed = at.lerp(next, (at.y - plane) / (at.y - next.y));
                if (!clear(at, landed, target.cell(), obstacles)) return null;
                return insideCell(landed, target.cell()) ? landed : null;
            }
            if (!clear(at, next, target.cell(), obstacles)) return null;
            at = next;
            speed = speed.multiply(DRAG, DRAG, DRAG);
        }
        return null;
    }

    private static boolean insideCell(Vec3 point, BlockPos cell) {
        return point.x >= cell.getX() + EDGE_MARGIN && point.x <= cell.getX() + 1 - EDGE_MARGIN
                && point.z >= cell.getZ() + EDGE_MARGIN && point.z <= cell.getZ() + 1 - EDGE_MARGIN;
    }

    // 这一步分四小段，每段按掉落物的碰撞箱查周围的方块；要落进的那一格不算障碍。
    private static boolean clear(Vec3 from, Vec3 to, BlockPos target, Obstacles obstacles) {
        for (int part = 1; part <= 4; part++) {
            Vec3 point = from.lerp(to, part / 4.0);
            AABB item = new AABB(point.x - ITEM_HALF_WIDTH, point.y, point.z - ITEM_HALF_WIDTH,
                    point.x + ITEM_HALF_WIDTH, point.y + ITEM_HEIGHT, point.z + ITEM_HALF_WIDTH);
            for (int x = Mth.floor(item.minX); x <= Mth.floor(item.maxX); x++) {
                for (int y = Mth.floor(item.minY); y <= Mth.floor(item.maxY); y++) {
                    for (int z = Mth.floor(item.minZ); z <= Mth.floor(item.maxZ); z++) {
                        BlockPos cell = new BlockPos(x, y, z);
                        if (cell.equals(target)) continue;
                        List<AABB> boxes = obstacles.at(cell);
                        if (boxes == null) return false;
                        for (AABB box : boxes) {
                            if (box.intersects(item)) return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    // 原版由偏航、俯仰角算视线方向的式子：偏航南为 0、往西转，俯仰往下为正。
    static Vec3 direction(float yaw, float pitch) {
        float yawRad = yaw * Mth.DEG_TO_RAD;
        float pitchRad = pitch * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.sin(yawRad) * Mth.cos(pitchRad), -Mth.sin(pitchRad), Mth.cos(yawRad) * Mth.cos(pitchRad));
    }
}
