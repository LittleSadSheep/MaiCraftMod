package org.maiwithu.maicraft.task.chain;

import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;
import org.maiwithu.maicraft.task.reflex.Reflex;

/**
 * Compatibility descriptor for the former second-person speaking pose.
 *
 * <p>A first-person local body has no separate nearby body to face, so this
 * pose never competes for control.</p>
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
