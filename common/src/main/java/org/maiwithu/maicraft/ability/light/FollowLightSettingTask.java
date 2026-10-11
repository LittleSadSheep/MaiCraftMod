// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.light;

import org.maiwithu.maicraft.behavior.acquire.OffhandContents;
import org.maiwithu.maicraft.behavior.lighting.FollowLighting;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 随行补光开关的任务：把设置落到会话设置上，当场完成。enable 的结果写明"开关已开，插灯跟着走路发生"——
 * 配置成功不是覆盖承诺；disable 时已插下的灯留着。真正的插灯由生存需求在走路途中插，这里不动手。
 */
final class FollowLightSettingTask extends PhasedTask<FollowLightSettingTask.Phase> {

    enum Phase { APPLY }

    private final FollowLightSettingInput input;
    private final FollowLighting followLighting;
    private final BackpackView backpack;
    private final OffhandContents offhand;

    FollowLightSettingTask(FollowLightSettingInput input, FollowLighting followLighting,
                           BackpackView backpack, OffhandContents offhand) {
        super("随行补光开关", Phase.APPLY, new ProgressTracker(40, 100));
        this.input = input;
        this.followLighting = followLighting;
        this.backpack = backpack;
        this.offhand = offhand;
    }

    @Override protected Action enter(Phase phase) {
        return null;
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        if (input.enable()) {
            followLighting.enable(input.minimumLight());
            return Next.done(TaskResult.done("随行补光已开（阈值 " + followLighting.minimumLight()
                    + "）：插灯跟着走路发生，走到暗处且包里有火把时顺手插一支"));
        }
        followLighting.disable();
        return Next.done(TaskResult.done("随行补光已关：已插下的灯留着，不再顺手插新的"));
    }

    @Override protected ResultDetails details() {
        return LightModule.status(followLighting, backpack, offhand);
    }
}
