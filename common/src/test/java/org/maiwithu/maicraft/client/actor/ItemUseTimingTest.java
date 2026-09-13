package org.maiwithu.maicraft.client.actor;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.core.act.Interaction;

/** 真正推进原版面包持用倒计时；屏蔽渲染副作用，不用手动扣食物或计数假动作代替 32 刻过程。 */
public final class ItemUseTimingTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        breadSurvivesVanillaReleaseCheck(); foreignUseAndRevocationStayUntouched(); staleFoodSlotIsNotRebound();
        System.out.println("ItemUseTimingTest: native bread countdown, scoped held input, single completion and ownership guards passed");
    }

    private static void breadSurvivesVanillaReleaseCheck() throws Exception {
        try (var f = new Fixture()) {
            int duration = f.player.getMainHandItem().getUseDuration(f.player);
            check(duration == 32, "the installed native bread item must take 32 game ticks");
            Interaction interaction = Interaction.useInAir(f.player, InteractionHand.MAIN_HAND, Interaction.Timing.hold());
            check(interaction.tick() == Interaction.Status.RUNNING && f.player.isUsingItem() && f.h.mode.items == 1,
                    "one ordinary native item use starts the actual LocalPlayer animation");
            for (int tick = 1; tick <= duration; tick++) {
                // 与 Minecraft.handleKeybinds 的原版分支相同：没保持使用键就会当场松手。
                if (f.player.isUsingItem() && !ItemUseInputLease.project(f.h.h.minecraft, false)) f.h.mode.releaseUsingItem(f.player);
                check(f.player.isUsingItem(), "native food use must not be released before its full duration");
                f.player.nativeUsingTick();
                check(f.player.getUseItemRemainingTicks() == duration - tick, "LivingEntity must advance its real use-item countdown once per game tick");
                f.h.nextTick();
                check(interaction.tick() == Interaction.Status.RUNNING, "client animation alone does not invent server completion");
                check(f.h.mode.items == 1 && f.h.mode.releases == 0, "waiting never replays the use request or releases it early");
            }
            check(f.player.effects > 0, "native food-use timing must reach its animation-effect callbacks");
            // 客户端倒计时结束仍等服务器的结束状态；这里只测试持用时序，不伪造服务端扣食物证明。
            f.player.stopUsingItem();
            check(!ItemUseInputLease.project(f.h.h.minecraft, false), "completion cannot keep the key down to eat a second bread");
            f.h.nextTick(); check(interaction.tick() == Interaction.Status.DONE, "native stopped-use state ends the held interaction");
            interaction.stop(); check(f.h.mode.items == 1 && f.h.mode.releases == 0, "cleanup neither restarts food nor releases another action");
            check(f.player.getMainHandItem().getCount() == 2, "the timing fixture never grants or manually consumes food");
        }
    }

    private static void foreignUseAndRevocationStayUntouched() throws Exception {
        try (var f = new Fixture()) {
            f.player.startUsingItem(InteractionHand.MAIN_HAND);
            var foreign = Interaction.useInAir(f.player, InteractionHand.MAIN_HAND, Interaction.Timing.hold());
            check(foreign.tick() == Interaction.Status.FAILED && f.h.mode.items == 0, "an already running use is not adopted by a new eat task");
            foreign.stop(); check(f.player.isUsingItem() && f.h.mode.releases == 0, "failed ownership cannot release the foreign use");
            f.player.stopUsingItem();
            var owned = Interaction.useInAir(f.player, InteractionHand.MAIN_HAND, Interaction.Timing.hold());
            owned.tick(); f.h.nextTick(); owned.tick();
            check(ItemUseInputLease.project(f.h.h.minecraft, false), "the current owned use has a live input projection");
            f.h.h.actions.revokeForBoundary("test human handoff");
            check(!ItemUseInputLease.project(f.h.h.minecraft, false) && ItemUseInputLease.project(f.h.h.minecraft, true),
                    "revocation drops only projected input and preserves the actual human key");
            owned.stop(); check(f.h.mode.releases == 0, "a revoked receipt cannot release a later owner's use");
        }
    }

    private static void staleFoodSlotIsNotRebound() throws Exception {
        try (var f = new Fixture()) {
            var owned = Interaction.useInAir(f.player, InteractionHand.MAIN_HAND, Interaction.Timing.hold());
            owned.tick(); f.h.nextTick(); owned.tick();
            f.h.inventory.setItem(1, new ItemStack(Items.BREAD, 2)); f.h.inventory.selected = 1;
            check(!ItemUseInputLease.project(f.h.h.minecraft, false), "identical food in another hotbar slot is still another held selection");
            f.h.nextTick(); check(owned.tick() == Interaction.Status.FAILED, "renewing cannot silently rebind the old use to a new slot");
            owned.stop(); check(f.h.mode.releases == 0, "the stale food task leaves the replacement hand alone");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final InteractionWorldTestHarness h = new InteractionWorldTestHarness();
        final TimingPlayer player;
        Fixture() throws Exception {
            // 复用真实交互夹具的已初始化身体；只替换渲染回调，所有持用状态及倒计时仍用原版实现。
            player = h.h.allocate(TimingPlayer.class);
            for (Class<?> type = LocalPlayer.class; type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    field.setAccessible(true); field.set(player, field.get(h.player));
                }
            }
            h.h.body.bodyReplaced(player, true); player.input = new Input(); h.h.body.fulfillAutomationRequest(player);
            ActorControlTestHarness.field(ActorControlTestHarness.class, "player").set(h.h, player);
            ActorControlTestHarness.field(InteractionWorldTestHarness.class, "player").set(h, player);
            h.h.minecraft.player = player;
            ActorControlTestHarness.field(ClientActorBoundary.class, "observedPlayer").set(h.h.actor, player);
            ActorControlTestHarness.field(ClientActorBoundary.class, "observedPlayer").set(h.actor, player);
            ActorControlTestHarness.field(Level.class, "isClientSide").setBoolean(h.level, true);
            ActorControlTestHarness.field(Entity.class, "random").set(player, net.minecraft.util.RandomSource.create(1));
            h.inventory.setItem(0, new ItemStack(Items.BREAD, 2)); h.inventory.selected = 0;
            player.getFoodData().setFoodLevel(0);
            h.mode.itemUse = p -> p.getMainHandItem().use(h.level, p, InteractionHand.MAIN_HAND);
            h.nextTick();
        }
        @Override public void close() throws Exception { h.h.actions.revokeForBoundary("fixture ended"); h.close(); }
    }
    private static final class TimingPlayer extends LocalPlayer {
        int effects;
        private TimingPlayer() { super(null, null, null, null, null, false, false); }
        void nativeUsingTick() { super.updateUsingItem(getUseItem()); }
        @Override protected void triggerItemUseEffects(ItemStack stack, int count) { effects++; }
        @Override public void swing(InteractionHand hand) { /* 无渲染器和网络，但持用动画状态仍由原版维护。 */ }
        @Override public void setSprinting(boolean sprinting) { /* 进食夹具没有移动。 */ }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
