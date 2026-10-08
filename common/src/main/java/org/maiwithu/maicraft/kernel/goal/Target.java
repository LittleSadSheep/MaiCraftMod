// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import java.util.Objects;

/**
 * 统一目标模型：全接口只有这一种"指一个地方或一个东西"的方式（docs/design/07 第 4 节）。
 *
 * <p>"某片区域"等于任意目标加上参数 radius；"最近的某种东西"不是一种目标，
 * 要么先观察拿到句柄再用 {@link Seen}，要么由能力按参数自己去找最近的。
 */
public sealed interface Target {

    TargetKind kind();

    /** 当前位置。 */
    record Here() implements Target {
        @Override public TargetKind kind() {
            return TargetKind.HERE;
        }
    }

    /** 观察句柄：e 开头是实体，f 开头是地形特征，b 开头是方块或设施，例如 e12。句柄过期时能力以目标已消失结束。 */
    record Seen(String ref) implements Target {
        public Seen {
            if (ref == null || !ref.matches("[efb][0-9]+")) {
                throw new IllegalArgumentException("观察句柄应形如 e12、f3、b5：" + ref);
            }
        }

        @Override public TargetKind kind() {
            return TargetKind.SEEN;
        }
    }

    /** 记住的地点，或附近告示牌上的文字。 */
    record Landmark(String name) implements Target {
        public Landmark {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("地标名不能为空");
        }

        @Override public TargetKind kind() {
            return TargetKind.LANDMARK;
        }
    }

    /** 坐标；y 可以省略（null），到场后再解析可站立的高度，不把没核实的高度直接交给步行引擎。 */
    record Position(int x, Integer y, int z, String dimension) implements Target {
        @Override public TargetKind kind() {
            return TargetKind.POSITION;
        }
    }

    /** 一位玩家。 */
    record Player(String name) implements Target {
        public Player {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("玩家名不能为空");
        }

        @Override public TargetKind kind() {
            return TargetKind.PLAYER;
        }
    }

    /** 方向加距离，例如"往北 100 格"、"往前 20 格"。 */
    record Direction(Bearing bearing, int distance) implements Target {
        public Direction {
            Objects.requireNonNull(bearing, "bearing");
            if (distance <= 0) throw new IllegalArgumentException("距离必须为正：" + distance);
        }

        @Override public TargetKind kind() {
            return TargetKind.DIRECTION;
        }
    }

    /** 前面某一步确认过的位置；step 为 null 表示上一步。 */
    record Previous(Integer step) implements Target {
        @Override public TargetKind kind() {
            return TargetKind.PREVIOUS;
        }
    }

    /** 方向：相对角色朝向的前后左右，或罗盘方向。 */
    enum Bearing {
        FORWARD, BACKWARD, LEFT, RIGHT, NORTH, SOUTH, EAST, WEST
    }
}
