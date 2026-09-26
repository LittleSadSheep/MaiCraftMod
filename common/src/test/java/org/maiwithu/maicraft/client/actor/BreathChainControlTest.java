package org.maiwithu.maicraft.client.actor;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneRuntime;
import org.maiwithu.maicraft.core.task.chain.BreathChain;
import org.maiwithu.maicraft.task.Task;

/** 调用真实换气链和身体输入端口：顶板下先横游，开口下才上浮，补满气之前不重新下潜。 */
public final class BreathChainControlTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var w = new InteractionWorldTestHarness()) {
            var player = w.h.allocate(WetPlayer.class);
            for (Class<?> type = LocalPlayer.class; type != Object.class; type = type.getSuperclass())
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    field.setAccessible(true); field.set(player, field.get(w.player));
                }
            // 复用真实身体租约，只替换当前浸水观测；测试按节点移动身体，不伪装成实机游泳物理验收。
            w.h.body.bodyReplaced(player, true); player.input = new Input(); w.h.body.fulfillAutomationRequest(player);
            ActorControlTestHarness.field(ActorControlTestHarness.class, "player").set(w.h, player);
            ActorControlTestHarness.field(InteractionWorldTestHarness.class, "player").set(w, player);
            w.h.minecraft.player = player;
            ActorControlTestHarness.field(ClientActorBoundary.class, "observedPlayer").set(w.h.actor, player);
            ActorControlTestHarness.field(ClientActorBoundary.class, "observedPlayer").set(w.actor, player);
            player.wet = player.eyesWet = true; player.setAirSupply(260); w.position(new Vec3(2.5, 1, 3.5));
            ActorControlTestHarness.field(LocalPlayer.class, "dimensions").set(player, EntityDimensions.scalable(.6F, 1.8F));
            ActorControlTestHarness.field(LocalPlayer.class, "deltaMovement").set(player, Vec3.ZERO);
            for (int x = 1; x < 15; x++) for (int z = 1; z < 15; z++) {
                for (int y = 1; y <= 2; y++) w.set(new BlockPos(x, y, z), Blocks.WATER.defaultBlockState());
                if (x < 8) w.set(new BlockPos(x, 3, z), Blocks.STONE.defaultBlockState());
            }
            w.nextTick(); var chain = new BreathChain();
            check(chain.canRun(player), "sealed ceiling triggers early enough to include a swimming detour");
            BodyControlPort.Movement movement = BodyControlPort.Movement.STOPPED;
            for (int tick = 0; tick < 500 && movement.forward() == 0 && movement.strafe() == 0; tick++) {
                chain.tick(player);
                movement = (BodyControlPort.Movement) ActorControlTestHarness.field(DefaultBodyControlPort.class, "movement").get(w.h.body);
                w.nextTick();
            }
            check((Math.abs(movement.forward()) + Math.abs(movement.strafe())) > .5 && !movement.jumping(), "under the roof, follow the horizontal escape edge without pressing upward");
            chain.stop(player, Task.StopReason.BODY_GONE);
            w.position(new Vec3(10.5, 1, 3.5)); w.nextTick(); chain = new BreathChain(); chain.tick(player);
            movement = (BodyControlPort.Movement) ActorControlTestHarness.field(DefaultBodyControlPort.class, "movement").get(w.h.body);
            check(movement.jumping() && movement.forward() == 0 && movement.strafe() == 0, "open water column permits direct ascent");
            player.eyesWet = false; player.setAirSupply(270); w.nextTick();
            check(chain.canRun(player), "emerging eyes do not end the refill episode early");
            player.setAirSupply(300); check(!chain.canRun(player), "full authoritative air releases the rescue");
            player.fallDistance = 0; check(EmbeddedBaritoneRuntime.handOffForBreathing(player), "water-buffered body can leave a landing recovery wait");
            player.fallDistance = 4; check(!EmbeddedBaritoneRuntime.handOffForBreathing(player), "an unbuffered fall retains landing control");
        }
        System.out.println("BreathChainControlTest: passed");
    }
    private static final class WetPlayer extends LocalPlayer {
        boolean wet, eyesWet;
        private WetPlayer() { super(null, null, null, null, null, false, false); }
        @Override public boolean isInWater() { return wet; }
        @Override public boolean isEyeInFluid(TagKey<Fluid> tag) { return eyesWet; }
        @Override public boolean hasEffect(Holder<MobEffect> effect) { return false; }
        @Override public void setSprinting(boolean value) { /* 测试不启动真实移动网络，身体按键仍由生产端口接收。 */ }
    }
    private static void check(boolean value, String detail) { if (!value) throw new AssertionError(detail); }
}
