// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.concurrent.atomic.AtomicReference;

/** 不启动游戏，只验证放置试算读取的潜行、视角和作用域边界；测试名字保留给已有回归入口。 */
public final class PlacementSneakProjectionTest {
    private record PlayerIdentity(int value) {}
    private record Read(boolean sneak, float yaw, float pitch) {}
    private static final Read ACTUAL = new Read(false, 12, 34);
    private static final Read OUTER = new Read(true, 180, 25);

    private PlacementSneakProjectionTest() {}

    public static void main(String[] args) throws Exception {
        Object player = new PlayerIdentity(1), other = new PlayerIdentity(1);
        check(player.equals(other) && player != other, "身份测试必须区分值相等与同一玩家对象");
        check(read(player).equals(ACTUAL), "预测作用域外保留真实姿态与角度");
        // 只潜行的旧调用既不能改变真实角度，也不能继承另一个候选的角度。
        for (boolean actual : new boolean[]{false, true}) {
            PlacementPlayerProjection.withCandidate(player, !actual, () -> {
                check(PlacementPlayerProjection.project(player, actual) != actual, "候选潜行替换本次玩家读取");
                check(PlacementPlayerProjection.project(other, actual) == actual, "值相等的其他玩家不能被投影");
                check(read(player).yaw() == ACTUAL.yaw() && read(player).pitch() == ACTUAL.pitch(), "旧入口不替换角度");
                return null;
            });
            check(PlacementPlayerProjection.project(player, actual) == actual, "完成潜行试算后恢复原始输入读取");
        }
        PlacementPlayerProjection.withCandidate(player, true, OUTER.yaw(), OUTER.pitch(), () -> {
            check(read(player).equals(OUTER) && read(other).equals(ACTUAL), "潜行与两种角度必须绑定同一指定玩家");
            nestedScopes(player, other);
            invalidAnglesLeaveTheOuterScope(player);
            return null;
        });
        check(read(player).equals(ACTUAL), "正常退出后不能残留候选角度");
        // 原生模组可能抛异常或Error；两种退出都要撤销整个读取投影，而不是只撤销潜行。
        try { PlacementPlayerProjection.withCandidate(player, true, 90, -65, () -> { throw new AssertionError("native error"); }); }
        catch (AssertionError expected) { check(expected.getMessage().equals("native error"), "必须原样传播原生Error"); }
        check(read(player).equals(ACTUAL), "Error路径不得留下姿态或角度");
        threadIsolation(player);
        System.out.println("PlacementSneakProjectionTest: posture/angle identity, nesting, finite-input and thread isolation passed");
    }

    private static void nestedScopes(Object player, Object other) {
        try {
            PlacementPlayerProjection.withCandidate(player, false, -90, -30, () -> {
                check(read(player).equals(new Read(false, -90, -30)), "内层完整候选同时遮蔽外层姿态与角度");
                throw new IllegalStateException("native prediction failed");
            });
            throw new AssertionError("原生异常不得被吞掉");
        } catch (IllegalStateException expected) {
            check(read(player).equals(OUTER), "内层异常后必须恢复外层完整候选");
        }
        PlacementPlayerProjection.withCandidate(player, false, () -> {
            check(read(player).equals(ACTUAL), "只潜行内层显式遮蔽外层角度，不能偷偷继承");
            return null;
        });
        check(read(player).equals(OUTER), "旧入口返回后恢复外层角度");
        PlacementPlayerProjection.withCandidate(other, false, 45, 70, () -> {
            check(read(other).equals(new Read(false, 45, 70)), "另一个候选玩家读取自己的视角");
            check(read(player).equals(ACTUAL), "内层换玩家期间外层玩家读取真实值");
            return null;
        });
        check(read(player).equals(OUTER), "另一个玩家试算完毕后恢复原玩家作用域");
    }

    private static void invalidAnglesLeaveTheOuterScope(Object player) {
        // 非有限角度在进入原生方块逻辑之前拒绝；拒绝不得覆盖正在进行的合法外层候选。
        for (float bad : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            for (boolean yaw : new boolean[]{false, true}) {
                boolean rejected = false;
                try {
                    PlacementPlayerProjection.withCandidate(player, false, yaw ? bad : 0, yaw ? 0 : bad,
                            () -> { throw new AssertionError("非法角度不得运行原生预测回调"); });
                } catch (IllegalArgumentException expected) { rejected = true; }
                check(rejected && read(player).equals(OUTER), "非法yaw或pitch必须拒绝并保持外层作用域");
            }
        }
    }

    private static void threadIsolation(Object player) {
        // 同一对象在另一线程上可能被渲染或观测；只读候选也不能跨线程污染那里看到的真实状态。
        AtomicReference<Read> crossThread = new AtomicReference<>();
        Thread worker = new Thread(() -> crossThread.set(read(player)), "placement-read-projection-test");
        PlacementPlayerProjection.withCandidate(player, true, OUTER.yaw(), OUTER.pitch(), () -> {
            worker.start();
            try { worker.join(2000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
            check(!worker.isAlive() && ACTUAL.equals(crossThread.get()), "另一线程须读取真实潜行、yaw和pitch");
            check(read(player).equals(OUTER), "另一线程读取不能清除本线程候选");
            return null;
        });
        check(read(player).equals(ACTUAL), "线程隔离检查结束后也必须清除作用域");
    }

    private static Read read(Object player) {
        return new Read(PlacementPlayerProjection.project(player, ACTUAL.sneak()),
                PlacementPlayerProjection.projectYaw(player, ACTUAL.yaw()), PlacementPlayerProjection.projectPitch(player, ACTUAL.pitch()));
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
