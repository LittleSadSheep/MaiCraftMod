// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import java.util.Objects;

/**
 * 目标对象：能力要去的地方或要处理的东西。全接口只有这一种"指一个地方或一个东西"的方式。
 *
 * <p>"某片区域"等于任意目标对象加上参数 radius；"最近的某种东西"不是一种目标对象，
 * 要么先观察拿到观察编号再用 {@link Seen}，要么由能力按参数自己去找最近的。
 */
public sealed interface Target {

    TargetKind kind();

    /** 角色当前的位置。 */
    record Here() implements Target {
        @Override public TargetKind kind() {
            return TargetKind.HERE;
        }
    }

    /**
     * 观察时看到的东西，用观察编号指定：e 开头是实体，f 开头是地形特征，b 开头是方块或设施，例如 e12。
     * 编号对应的东西不在了（走远、被拆）时，能力以 TARGET_GONE 结束。
     */
    record Seen(String id) implements Target {
        public Seen {
            if (id == null || !id.matches("[efb][0-9]+")) {
                throw new IllegalArgumentException("观察编号应形如 e12、f3、b5：" + id);
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

    /** 坐标；y 可以省略（null），到了附近再找能站的高度，不把没核实的高度直接交给寻路。 */
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

    /** 朝某个方向走多远，例如"往北 100 格"、"往前 20 格"。 */
    record Direction(Toward toward, int distance) implements Target {
        public Direction {
            Objects.requireNonNull(toward, "toward");
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

    /** 朝哪边：相对角色朝向的前后左右，或东南西北。 */
    enum Toward {
        FORWARD, BACKWARD, LEFT, RIGHT, NORTH, SOUTH, EAST, WEST
    }
}
