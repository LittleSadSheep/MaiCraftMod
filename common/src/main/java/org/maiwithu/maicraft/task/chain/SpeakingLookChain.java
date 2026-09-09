package org.maiwithu.maicraft.task.chain;

import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;
import org.maiwithu.maicraft.task.reflex.Reflex;

/**
 * 旧的“说话时看向另一名角色”姿势占位，当前没有注册或生产调用。
 * 第一人称模式下 canRun 永远返回 false；tick 不发动作，stop 也为空。
 */
public final class SpeakingLookChain implements Task, Reflex {

    @Override
    public boolean canRun(LocalPlayer player) {
        return false;
    }

    @Override
    public TaskState tick(LocalPlayer player) {
        return TaskState.RUNNING;
    }

    @Override
    public void stop(LocalPlayer player, StopReason why) {
    }

    @Override
    public String name() {
        return "speaking_look";
    }

    @Override
    public String id() {
        return name();
    }

    @Override
    public String describe() {
        return "第一人称说话不额外接管视角";
    }
}
