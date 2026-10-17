// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import java.util.List;

/**
 * 近处走位：这一刻往哪迈一步。纯函数，不碰游戏。
 *
 * <p>在八个水平方向（前后左右与四个斜向）和站住里挑一个：落点离目标落在距离带里、离每一只别的怪都在它的危险半径外、
 * 脚下踩得住；一格高的台阶带跳。都做不到时挑"挨打最少"的那个——进别的怪危险半径比离带子远更糟，
 * 所以身后是墙就往侧面挪，被两侧夹住就往开口方向退，让追兵排成一线。原先只会面朝一只直线进退，
 * 身后被方块挡住就站着挨打，别的怪走到身后也不管。
 *
 * <p>每刻重新挑，所以被击退、被推开也会按带子收回来；站在带里又没有危险时站住，不来回抖。
 */
public final class Footwork {

    /** 评估一步的落点有多远（格）：一两刻走不出这么远，看一步就够。 */
    static final double STRIDE = 1.0;
    /** 迈步本身的一点代价：两边一样好时站住，不来回抖。 */
    static final double MOVE_COST = 0.05;
    /** 进了怪够得着我的地方比离带子远糟几倍：挨打要命，离远了只是要多走两步。 */
    static final double HIT_WEIGHT = 3.0;

    private static final double DIAGONAL = Math.sqrt(0.5);
    /** 八个方向：东、东南、南、西南、西、西北、北、东北（x 向东、z 向南）。 */
    private static final double[][] DIRECTIONS = {
            {1, 0}, {DIAGONAL, DIAGONAL}, {0, 1}, {-DIAGONAL, DIAGONAL},
            {-1, 0}, {-DIAGONAL, -DIAGONAL}, {0, -1}, {DIAGONAL, -DIAGONAL}};

    private Footwork() {}

    /**
     * 一只要躲开的：位置与它的危险半径（水平中心距，进了就会挨打）。
     *
     * @param radius 离它比这近就算进了它够得着的地方
     */
    public record Hazard(double x, double z, double radius) {}

    /**
     * 要守的距离带：离焦点（要打的那只）的水平中心距落在 [inner, outer] 里。
     * 内沿是它够得着我的距离，比内沿近就是在挨打；外沿是我够得着它的距离，比外沿远只是打不着。
     */
    public record Band(double x, double z, double inner, double outer) {

        /** 落点离带子差多远：在带里为 0；太近按挨打算重，太远按多走算轻。 */
        double cost(double px, double pz) {
            double distance = Math.hypot(px - x, pz - z);
            if (distance < inner) return (inner - distance) * HIT_WEIGHT;
            return Math.max(0, distance - outer);
        }
    }

    /**
     * 这一刻的站位要求。
     *
     * @param band    要守的距离带；为 null 时只躲开（撤离、躲爆炸、冷却中没有目标）
     * @param hazards 别的要躲开的怪
     * @param sprint  疾跑（躲爆炸、撤离：引信只有一秒半，先跑后算）
     */
    public record Stance(Band band, List<Hazard> hazards, boolean sprint) {
        public Stance {
            hazards = List.copyOf(hazards);
        }

        /** 这一点的总代价：带子的差距加上进了几只怪的危险半径多深。 */
        double cost(double px, double pz) {
            double cost = band == null ? 0 : band.cost(px, pz);
            for (Hazard hazard : hazards) {
                double intrusion = hazard.radius() - Math.hypot(px - hazard.x(), pz - hazard.z());
                if (intrusion > 0) cost += intrusion * HIT_WEIGHT;
            }
            return cost;
        }
    }

    /** 朝某个方向迈一步脚下怎样：过不去、平地、要跳上一格台阶。 */
    public enum Footing { BLOCKED, FLAT, STEP_UP }

    /** 脚下的路：朝这个方向（水平单位向量）迈一步能不能落脚。生产实现读当刻世界，测试换替身。 */
    @FunctionalInterface
    public interface Ground {
        Footing toward(double dx, double dz);
    }

    /**
     * 挑出的一步：朝哪个方向（水平单位向量；站住时两个都是 0）、要不要跳、要不要疾跑。
     */
    public record Step(double dx, double dz, boolean jump, boolean sprint) {
        public static final Step STAND = new Step(0, 0, false, false);

        public boolean standing() {
            return dx == 0 && dz == 0;
        }
    }

    /**
     * 挑这一刻的一步：站住与八个方向里代价最小的，过不去的方向不算。
     *
     * @param x 我脚下的水平位置
     * @param z 我脚下的水平位置
     */
    public static Step choose(double x, double z, Stance stance, Ground ground) {
        double best = stance.cost(x, z);
        Step chosen = Step.STAND;
        for (double[] direction : DIRECTIONS) {
            Footing footing = ground.toward(direction[0], direction[1]);
            if (footing == Footing.BLOCKED) continue;
            double cost = stance.cost(x + direction[0] * STRIDE, z + direction[1] * STRIDE) + MOVE_COST;
            if (cost < best - 1e-9) {
                best = cost;
                chosen = new Step(direction[0], direction[1], footing == Footing.STEP_UP, stance.sprint());
            }
        }
        return chosen;
    }

    /** 这一点挨不挨打：在不在哪只怪（含带子焦点那只）够得着的地方。 */
    public static boolean exposed(double x, double z, Stance stance) {
        if (stance.band() != null && Math.hypot(x - stance.band().x(), z - stance.band().z()) < stance.band().inner()) {
            return true;
        }
        for (Hazard hazard : stance.hazards()) {
            if (Math.hypot(x - hazard.x(), z - hazard.z()) < hazard.radius()) return true;
        }
        return false;
    }
}
