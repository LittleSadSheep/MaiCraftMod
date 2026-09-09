package org.maiwithu.maicraft.core.tools.interact;

import static org.maiwithu.maicraft.task.TaskDispatch.ctx;
import static org.maiwithu.maicraft.task.TaskDispatch.runSync;

import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import org.maiwithu.maicraft.agent.tool.Schema;
import org.maiwithu.maicraft.core.tools.SleepOps;
import org.maiwithu.maicraft.task.TaskResult;
import net.minecraft.client.player.LocalPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 内部“上床”工具：让身边的床变成一项睡觉任务，等游戏确认已经躺下才回复。
 * 找远处的床、走过去、必要时摆床，由外层 AbilityAdapter 的睡觉流程安排。
 * 它不等天亮；若还要等天亮，需要再执行等待条件的步骤。
 */
public final class SleepTool implements MaiCraftTool {

    private static final Gson GSON = new Gson();
    private final SleepOps impl = new SleepOps();

    private record Args(Integer x, Integer y, Integer z) {}

    @Override
    public String name() {
        return "sleep";
    }

    @Override
    public String description() {
        return "Get into a loaded bed you are already standing next to, and report whether you are actually "
                + "asleep. It does NOT travel: find a bed with scan_blocks using #minecraft:beds (that "
                + "one tag covers every colour), goto it, then call this. Give x/y/z to use one specific "
                + "bed, or omit them for whichever bed is in reach. Succeeds only when the server "
                + "confirms you are sleeping; otherwise it returns the native receipt detail for the "
                + "intent layer to present before choosing a recovery. "
                + "\"Only at night\" means wait for the night condition rather than blind retry. "
                + "\"Too far away\" means goto. "
                + "Returns the moment you lie down; night passes on its own and needs no tool.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .nullableNumber("x", "X of the bed. Leave null to use whichever bed is in reach.")
                .nullableNumber("y", "Y of the bed. Leave null to use whichever bed is in reach.")
                .nullableNumber("z", "Z of the bed. Leave null to use whichever bed is in reach.")
                .build();
    }

    @Override
    public void onGameCall(String toolCallId, JsonObject args, LocalPlayer self, Consumer<String> reply) {
        // 先确定床在哪；不具备开始条件就立即说明原因，可以开始才把任务交给调度器。
        Args a = GSON.fromJson(args, Args.class);
        SleepOps.Plan plan = impl.plan(
                a == null ? null : a.x(),
                a == null ? null : a.y(),
                a == null ? null : a.z(),
                self,
                ctx(toolCallId, self));
        if (!plan.executable()) {
            reply.accept(TaskResult.fail(plan.refusal()).toJson());
            return;
        }
        runSync(self, plan.task(), reply);
    }
}
