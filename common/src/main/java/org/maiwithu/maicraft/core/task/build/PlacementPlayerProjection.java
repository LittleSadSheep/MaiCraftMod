// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.Objects;
import java.util.function.Supplier;

/** 试算放块时只投影选中玩家的读取结果；不转动真实镜头、不按键，也不修改实体字段或发送包。 */
public final class PlacementPlayerProjection {
    private record Candidate(Object player, boolean sneak, Float yaw, Float pitch) {}
    private static final ThreadLocal<Candidate> CURRENT = new ThreadLocal<>();

    private PlacementPlayerProjection() {}

    /** 旧入口只试算潜行；嵌套使用时角度仍取真实值，不能借用外层另一种放法的视角。 */
    public static <T> T withCandidate(Object player, boolean sneak, Supplier<T> prediction) {
        return scoped(new Candidate(Objects.requireNonNull(player, "placement prediction player"), sneak, null, null), prediction);
    }

    /** 某些模组直接读取玩家角度；让它与放置上下文使用同一候选视角，非法角度不能进入原生试算。 */
    public static <T> T withCandidate(Object player, boolean sneak, float yaw, float pitch, Supplier<T> prediction) {
        Objects.requireNonNull(player, "placement prediction player");
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch))
            throw new IllegalArgumentException("placement prediction angles must be finite");
        return scoped(new Candidate(player, sneak, yaw, pitch), prediction);
    }

    // 原生放置逻辑可以嵌套试算或抛出异常；退出时必须恢复外层同一作用域，不能残留到下一次真实游戏读取。
    private static <T> T scoped(Candidate candidate, Supplier<T> prediction) {
        Objects.requireNonNull(prediction, "placement prediction");
        Candidate previous = CURRENT.get();
        CURRENT.set(candidate);
        try { return prediction.get(); }
        finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    /** 只改变本次预测指定对象的潜行读取，其他玩家及作用域外读取都保留原生值。 */
    public static boolean project(Object player, boolean actual) {
        Candidate candidate = CURRENT.get();
        return candidate != null && candidate.player() == player ? candidate.sneak() : actual;
    }

    // 两种角度共用潜行作用域和对象身份，不让邻近实体或集成服务端玩家继承客户端候选视角。
    public static float projectYaw(Object player, float actual) {
        Candidate candidate = CURRENT.get();
        return candidate != null && candidate.player() == player && candidate.yaw() != null ? candidate.yaw() : actual;
    }

    public static float projectPitch(Object player, float actual) {
        Candidate candidate = CURRENT.get();
        return candidate != null && candidate.player() == player && candidate.pitch() != null ? candidate.pitch() : actual;
    }
}
