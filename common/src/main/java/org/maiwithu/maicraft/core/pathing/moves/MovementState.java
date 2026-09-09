package org.maiwithu.maicraft.core.pathing.moves;

import java.util.EnumMap;
import java.util.Map;

/**
 * 旧移动执行器本次更新的记录：到了哪个阶段、希望看向哪里、哪些键要按下或松开。它本身不控制游戏输入。
 */
public class MovementState {

    private MovementStatus status;
    private MovementTarget target = new MovementTarget();
    private final Map<Input, Boolean> inputStates = new EnumMap<>(Input.class);

    public MovementState setStatus(MovementStatus status) {
        this.status = status;
        return this;
    }

    public MovementStatus getStatus() {
        return status;
    }

    public MovementTarget getTarget() {
        return target;
    }

    public MovementState setTarget(MovementTarget target) {
        this.target = target;
        return this;
    }

    public MovementState setInput(Input input, boolean forced) {
        inputStates.put(input, forced);
        return this;
    }

    // 返回内部的可修改按键表；旧 Movement.update 读取后会清空，为下一次更新重新收集。
    public Map<Input, Boolean> getInputStates() {
        return inputStates;
    }

    /**
     * 期望视角:yaw/pitch 目标转角与 force 标志。
     * force=true 表示挖掘/放置需要真实对准;false 表示仅行走朝向,
     * 执行层可静默处理(只影响移动方向,不必真的转头)。
     */
    public static final class MovementTarget {

        private final boolean hasRotation;
        private final float yaw;
        private final float pitch;
        private final boolean forceRotations;

        /** 无视角要求。 */
        public MovementTarget() {
            this.hasRotation = false;
            this.yaw = 0;
            this.pitch = 0;
            this.forceRotations = false;
        }

        public MovementTarget(float yaw, float pitch, boolean forceRotations) {
            this.hasRotation = true;
            this.yaw = yaw;
            this.pitch = pitch;
            this.forceRotations = forceRotations;
        }

        public boolean hasRotation() {
            return hasRotation;
        }

        public float getYaw() {
            return yaw;
        }

        public float getPitch() {
            return pitch;
        }

        public boolean hasToForceRotations() {
            return forceRotations;
        }
    }
}
