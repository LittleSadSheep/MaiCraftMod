// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.SemanticResultView;
import org.maiwithu.maicraft.server.machine.create.CreateDeployerHandView;
import org.maiwithu.maicraft.task.TaskResult;

/** 持料事实与一次换物是否确认分开；初始未同步不能当空槽，同物同数也不能推定点击成功。 */
public final class CreateDeployerHandEvidenceTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var held = new ItemStack(Items.STONE,5); held.set(DataComponents.CUSTOM_NAME,Component.literal("原生工件身份"));
        check(!CreateDeployerHandEvidence.data(view(held,0),registries).containsKey("item_id"),"unsynchronized defaults remain unknown");
        var before = CreateDeployerHandEvidence.data(view(held,1),registries);
        check(before.get("count").equals(5) && Boolean.TRUE.equals(before.get("components_observed")),"native count and components are retained");
        held.setCount(2);
        check(before.get("count").equals(5) && ((JsonObject)before.get("stack")).get("count").getAsInt()==5,"later packets cannot mutate old receipt evidence");
        var empty = CreateDeployerHandEvidence.data(view(ItemStack.EMPTY,2),registries);
        check(empty.get("observation_status").equals("observed") && empty.get("count").equals(0),"synchronized empty differs from unknown");
        // 即使前后都已持有五件，交换动作仍保持未确认；通知只转述事实，不替模型宣称已装料或已生产。
        var failure = TaskResult.fail("native use unconfirmed",Map.of("outcome_uncertain",true,"deployer_hand_observation",
                Map.of("before",before,"after",before,"submitted_face","down")));
        var compact = IntentRuntime.class.getDeclaredMethod("compactAttentionResult",JsonObject.class); compact.setAccessible(true);
        var notice = (JsonObject)compact.invoke(null,JsonParser.parseString(SemanticResultView.result(failure).toJson()).getAsJsonObject());
        var data = notice.getAsJsonObject("data");
        check(data.get("outcome_uncertain").getAsBoolean() && !failure.success(),"observed stock does not confirm a swap");
        check(data.getAsJsonObject("deployer_hand_observation").getAsJsonObject("before").get("count").getAsInt()==5,
                "attention keeps the stock that already existed before the click");
        System.out.println("CreateDeployerHandEvidenceTest: passed");
    }
    private static CreateDeployerHandView view(ItemStack stack,long revision) {
        return new CreateDeployerHandView() {
            public ItemStack maicraft$receivedHandStack() { return stack.copy(); }
            public long maicraft$handUpdateRevision() { return revision; }
        };
    }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
}
