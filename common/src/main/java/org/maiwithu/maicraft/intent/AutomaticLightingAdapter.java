// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.core.task.lighting.AutomaticLighting;
import org.maiwithu.maicraft.task.TaskResult;

/** 启停随行助手只修改当前会话设置；配置本身即时完成，绝不能替换仍在挖矿的任务。 */
final class AutomaticLightingAdapter {
    static final String ABILITY = "maicraft:auto_light";
    record Request(String action, int minimum, List<String> protectedLabels) {}

    static Request parse(Goal goal) {
        // 会话启动默认关闭；只有收到本能力请求时，省略 action 才表示开启，不能把查询当作开启授权。
        var args = goal.parameters();
        String action = "enable";
        if (args.has("action")) {
            if (!args.get("action").isJsonPrimitive() || !args.getAsJsonPrimitive("action").isString())
                throw new IllegalArgumentException("auto_light action must be enable, disable or status");
            action = args.get("action").getAsString();
        }
        if (!List.of("enable", "disable", "status").contains(action))
            throw new IllegalArgumentException("auto_light action must be enable, disable or status");
        // 每次启停都从本次参数重建配置；省略阈值会回到 8，不沿用上次开启时的自定义亮度。
        int minimum = 8;
        if (args.has("minimum_light")) {
            var value = args.get("minimum_light");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                    || value.getAsDouble() != value.getAsInt() || value.getAsInt() < 1 || value.getAsInt() > 13)
                throw new IllegalArgumentException("minimum_light must be an integer from 1 to 13; default 8");
            minimum = value.getAsInt();
        }
        // 顺序任务的保护名称随配置保留；这里只合并名称，是否能在当前世界解析由实际补光时检查。
        List<String> labels = new ArrayList<>(goal.inheritedProtectionLabels());
        if (args.has("protected_labels")) args.getAsJsonArray("protected_labels").forEach(label -> labels.add(label.getAsString()));
        return new Request(action, minimum, List.copyOf(labels));
    }

    static IntentAction adapt(Goal goal, LocalPlayer player) {
        Request request = parse(goal);
        var lighting = AutomaticLighting.get();
        // status 仍校验请求格式，但只重读当前路线；启停也不立即换副手或放火把，回执成功仅证明配置完成。
        if (!request.action().equals("status")) lighting.configure(player, request.action().equals("enable"), request.minimum(), request.protectedLabels());
        return new IntentAction.Report(TaskResult.ok("Automatic lighting " + request.action()
                + "; the current task continues. Configuration is not a coverage claim.", Map.of("automatic_lighting", lighting.snapshot(player))), null);
    }
}
