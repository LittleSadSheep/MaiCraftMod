// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.enchant.EnchantParameters;
import org.maiwithu.maicraft.intent.persistence.IntentStateCodec;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.core.task.enchant.EnchantTaskRecord;

/** 从公开目标检查真实台子绑定与成本契约；操作编号只测试持久身份，不模拟一次真实附魔。 */
public final class EnchantIntentTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        contracts(); nativeTarget(); stableSubmissionIdentity();
        System.out.println("EnchantIntentTest: public contract, observed table and durable operation identity passed");
    }

    private static void contracts() {
        // 沿用公开运行时的目标编译入口，只检查报价预算，夹具不接管玩家或提交附魔。
        var runtime = IntentRuntime.get(); var valid = goal(); runtime.compile(valid,1);
        check(EnchantParameters.parse(valid.parameters()).offerTier()==1, "未指定档位采用第一档，不暗选更贵报价");
        for (String field : List.of("max_levels_spent","max_lapis")) {
            JsonObject parameters=valid.parameters();parameters.remove(field);
            rejects(() -> runtime.compile(valid.withParameters(parameters),1));
            for (String invalid : List.of("-1","4","1.5","\"1\"","null")) {
                JsonObject next=valid.parameters();next.add(field,JsonParser.parseString(invalid));
                rejects(() -> runtime.compile(valid.withParameters(next),1));
            }
        }
        for (String field : List.of("slot","button","container_id","seed")) {
            JsonObject next=valid.parameters();next.addProperty(field,0);
            rejects(() -> runtime.compile(valid.withParameters(next),1));
        }
        JsonObject zero=valid.parameters();zero.addProperty("max_levels_spent",0);zero.addProperty("max_lapis",0);
        runtime.compile(valid.withParameters(zero),1);
        check(SemanticAbilityCatalog.describe(EnchantAbilityAdapter.ABILITY).toString().contains("required XP level"), "能力须说明门槛与扣费不同");
    }

    private static void nativeTarget() throws Exception {
        try (var h=new InteractionWorldTestHarness()) {
            var runtime=IntentRuntime.get();BlockPos table=new BlockPos(2,1,2);
            h.set(table,Blocks.ENCHANTING_TABLE.defaultBlockState());
            Goal targeted=goal().withTarget(new Goal.SemanticTarget("coordinates",null,
                    new Goal.WorldPosition(2,1,2,"minecraft:overworld"),null));
            IntentAction action=EnchantAbilityAdapter.adapt(targeted,h.player,runtime);
            check(action instanceof IntentAction.Native,"观察到真实台子后才转换为统一原生过程任务");
            var nativeRecord = ((IntentAction.Native) action).record();
            check(nativeRecord instanceof EnchantTaskRecord,
                    "旧入口必须复用已验收的附魔执行器");
            var enchant = (EnchantTaskRecord) nativeRecord;
            check(enchant.table.equals(table) && enchant.offerTier == 1 && enchant.maxLapis == 1,
                    "固定目标与预算没有改成裸菜单指令");
            h.set(table,Blocks.STONE.defaultBlockState());
            check(EnchantAbilityAdapter.adapt(targeted,h.player,runtime) instanceof IntentAction.Report,"台子消失时只返回缺失事实");
            check(h.blockUses()==0&&h.itemUses()==0,"目标适配不能自己点击世界或花掉材料");
        }
    }

    private static void stableSubmissionIdentity() {
        UUID id=UUID.randomUUID();Goal enchant=goal();
        var original=new IntentTaskRecord(id,null,enchant);UUID first=EnchantSubmissionBinding.operationId(original);
        Goal prerequisite=new Goal("maicraft:remember_place","记住台子",new Goal.SemanticTarget("coordinates",null,
                new Goal.WorldPosition(2,1,2,"minecraft:overworld"),null),"{\"label\":\"附魔台\"}","{}",List.of(),List.of());
        original.insertRecovery(prerequisite);
        original.addStepResult(new IntentTaskRecord.StepSnapshot(0,prerequisite.ability(),true,"identity fixture",TaskResult.ok("identity fixture").toJson()));
        check(EnchantSubmissionBinding.operationId(original).equals(first),"插入不同准备步骤不能给原消费换编号");
        var saved=IntentStateCodec.encode("e".repeat(64),List.of(),List.of(original),Map.of(),List.of());
        // 按正式恢复入口重建暂停任务，确保重连后回到原附魔步骤仍沿用同一次消费标识。
        var decoded=IntentStateCodec.decode(saved).tasks().getFirst();
        var restored=IntentTaskRecord.restored(decoded.id(),decoded.planId(),decoded.goal(),"enchant-test",
                decoded.steps(),decoded.stepIndex(),decoded.completed(),decoded.internalPositions(),
                decoded.internalAreaProtections(),decoded.attempts(),decoded.decision(),decoded.pendingAnswer(),
                decoded.terminal(),100);
        check(EnchantSubmissionBinding.operationId(restored).equals(first),"重启恢复后保留同一消费编号");
        Goal sequence=new Goal("maicraft:sequence","两次独立附魔",null,"{}","{}",List.of(),List.of(enchant,enchant));
        var repeated=new IntentTaskRecord(id,null,sequence);UUID before=EnchantSubmissionBinding.operationId(repeated);
        repeated.addStepResult(new IntentTaskRecord.StepSnapshot(0,enchant.ability(),true,"identity fixture",TaskResult.ok("identity fixture").toJson()));
        check(!EnchantSubmissionBinding.operationId(repeated).equals(before),"明确排列的两个相同目标拥有独立消费编号");
    }

    private static Goal goal() {
        return Goal.fromJson(JsonParser.parseString("""
            {"ability":"maicraft:enchant","outcome":"附魔一把铁镐","parameters":{
              "item_id":"minecraft:iron_pickaxe","max_levels_spent":1,"max_lapis":1}}
            """).getAsJsonObject());
    }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError("无效或未授权成本应被拒绝"); }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
}
