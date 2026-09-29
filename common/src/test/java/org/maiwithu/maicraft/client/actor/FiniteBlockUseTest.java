// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.act.Interaction;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.SemanticResultView;
import org.maiwithu.maicraft.task.TaskResult;

/** 持续方块使用到期后仍等最后一次回执；没有实际效果的点击不能因为计时结束变成成功。 */
public final class FiniteBlockUseTest {
    private static final BlockPos AT = new BlockPos(8, 1, 5);
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        for (boolean fallback : new boolean[]{true, false})
        for (boolean applied : new boolean[]{true, false}) try (var h = new InteractionWorldTestHarness()) {
            h.position(new Vec3(8.5, 1, 8.5));
            h.set(AT, Blocks.REDSTONE_LAMP.defaultBlockState());
            var hit = new BlockHitResult(new Vec3(8.5, 1.5, 6), Direction.SOUTH, AT, false);
            var use = Interaction.forHit(h.player, hit, Interaction.Button.USE, 100, fallback);
            check(use.submittedBlockHit()==null,"an aimed face is not yet a submitted native click");
            for (int tick = 0; tick < 80 && h.blockUses() == 0; tick++) { use.tick(); next(h); }
            check(h.blockUses() == 1, "the native right click was submitted once");
            check(use.submittedBlockHit()!=null && use.submittedBlockHit().getDirection()==Direction.SOUTH,
                    "receipt records the actual face submitted to native use");
            use.finishRepeating();
            check(use.tick() == Interaction.Status.RUNNING, "elapsed duration cannot complete a pending native use");
            if (applied) h.set(AT, Blocks.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, true));
            Interaction.Status status = Interaction.Status.RUNNING;
            for (int tick = 0; tick < 30 && status == Interaction.Status.RUNNING; tick++) { next(h); status = use.tick(); }
            check(status == (applied ? Interaction.Status.DONE : Interaction.Status.FAILED)
                    && use.confirmedUses() == (applied ? 1 : 0) && h.blockUses() == 1 && h.itemUses() == 0,
                    "only confirmed effects complete and finishing sends no further block or item uses");
            // 即使调用者允许普通物品兜底，原生超时也只能返回不确定，不能继续按一次右键。
            check(Boolean.valueOf(!applied).equals(use.useEvidence().get("outcome_uncertain"))
                    && Boolean.FALSE.equals(use.useEvidence().get("mechanical_retry_allowed")),
                    "uncertain and already-applied clicks cannot be mechanically retried");
            var compact = IntentRuntime.class.getDeclaredMethod("compactAttentionResult", JsonObject.class);
            compact.setAccessible(true);
            var notice = (JsonObject) compact.invoke(null, JsonParser.parseString(SemanticResultView.result(
                    TaskResult.fail("fixture use outcome", use.useEvidence())).toJson()).getAsJsonObject());
            check(notice.getAsJsonObject("data").get("outcome_uncertain").getAsBoolean() == !applied
                    && notice.getAsJsonObject("data").get("native_action_status").getAsString()
                        .equals(applied ? "CONFIRMED_APPLIED" : "UNCERTAIN"), "attention preserves native use uncertainty");
            use.stop();
        }
        System.out.println("FiniteBlockUseTest: passed");
    }
    private static void next(InteractionWorldTestHarness h) throws Exception {
        // 无头游戏刻同时推进正常的平滑转头时钟，仍由实际身体端口改变视角。
        ActorControlTestHarness.field(DefaultBodyControlPort.class, "lastLookUpdateNanos").setLong(h.h.body, System.nanoTime() - 50_000_000L);
        h.h.body.endTick(h.h.context); h.nextTick();
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
