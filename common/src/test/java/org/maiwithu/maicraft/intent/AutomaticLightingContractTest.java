// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.lighting.AutomaticLighting;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;
import org.maiwithu.maicraft.mcp.MaiCraftRuntimeFacade;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;
import org.maiwithu.maicraft.task.TaskState;

/** 通过公开执行边界开关补光，确认当前采集任务、请求幂等性和完整暗格回执都不受影响。 */
public final class AutomaticLightingContractTest {
    public static void main(String[] args) throws Exception {
        var constructor = IntentRuntime.class.getDeclaredConstructor(); constructor.setAccessible(true);
        var runtime = constructor.newInstance();
        field(IntentRuntime.class, "stateIdentity").set(runtime, new StateIdentity("8".repeat(64), Files.createTempDirectory("lighting-contract-")));
        var brainField = field(CompanionTickDispatcher.class, "brain"); Object previous = brainField.get(null);
        var brainType = Class.forName("org.maiwithu.maicraft.task.CompanionBrain");
        var brainConstructor = brainType.getDeclaredConstructor(); brainConstructor.setAccessible(true);
        var brain = brainConstructor.newInstance();
        var slot = field(brainType, "current").get(brain);
        var mining = new IntentTaskRecord(UUID.randomUUID(), null,
                new Goal("maicraft:acquire_items", "正在挖矿", null,
                        "{\"item_id\":\"minecraft:raw_iron\",\"count\":64}", "{}", List.of(), List.of()));
        mining.setState(TaskState.RUNNING); field(slot.getClass(), "record").set(slot, mining); brainField.set(null, brain);
        try (var h = new InteractionWorldTestHarness()) {
            var boundary = MaiCraftRuntimeFacade.class.getDeclaredMethod("dispatchExecution", Goal.class, LocalPlayer.class, Supplier.class);
            boundary.setAccessible(true);
            // 查询本身不构成开启授权；主任务进行中也应读到默认关闭状态。
            AutomaticLighting.get().reset();
            var initial = runtime.execute(h.player, goal("{\"action\":\"status\"}"), null, "lighting-initial-status");
            check(!initial.terminalSnapshot().result().getAsJsonObject("data").getAsJsonObject("automatic_lighting")
                    .get("enabled").getAsBoolean(), "默认状态查询不能暗中启用补光");
            for (String action : List.of("enable", "status", "disable")) {
                var goal = goal("{\"action\":\"" + action + "\"}");
                var result = (IntentTaskRecord) boundary.invoke(null, goal, h.player,
                        (Supplier<IntentTaskRecord>) () -> runtime.execute(h.player, goal, null, "lighting-" + action));
                check(result.getState() == TaskState.SUCCESS && result.terminalSnapshot().result().getAsJsonObject("data")
                        .has("automatic_lighting"), "配置与查询返回可读终态");
                check(result.terminalSnapshot().result().getAsJsonObject("data").getAsJsonObject("automatic_lighting")
                        .get("enabled").getAsBoolean() == !action.equals("disable"), "只有开启指令启用，关闭即时生效");
                check(field(slot.getClass(), "record").get(slot) == mining && mining.getState() == TaskState.RUNNING,
                        "配置不能替换、暂停或取消挖矿");
                check(runtime.execute(h.player, goal, null, "lighting-" + action) == result, "重试复用原任务");
            }
            check(h.blockUses() == 0 && h.itemUses() == 0, "设置与查询不直接发身体动作");
            for (String parameters : List.of("{\"minimum_light\":0}", "{\"minimum_light\":14}",
                    "{\"minimum_light\":8.5}", "{\"minimum_light\":\"8\"}", "{\"action\":\"toggle\"}")) {
                try { runtime.compile(goal(parameters), 0); throw new AssertionError("非法配置被接收: " + parameters); }
                catch (IllegalArgumentException expected) { }
            }
            check(SemanticAbilityCatalog.describe("maicraft:acquire_items").getAsJsonArray("tips").toString().contains("auto_light"),
                    "挖矿契约直接提供随行补光 tips");
        } finally { brainField.set(null, previous); AutomaticLighting.get().reset(); }
        System.out.println("AutomaticLightingContractTest: passed");
    }

    private static Goal goal(String parameters) {
        return new Goal("maicraft:auto_light", "随行补光设置", null, parameters, "{}", List.of(), List.of());
    }
    private static Field field(Class<?> type, String name) throws Exception {
        var result = type.getDeclaredField(name); result.setAccessible(true); return result;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
