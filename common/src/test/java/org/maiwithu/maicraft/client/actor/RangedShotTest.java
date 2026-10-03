package org.maiwithu.maicraft.client.actor;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.core.act.Ballistics;
import org.maiwithu.maicraft.core.act.Interaction;
import org.maiwithu.maicraft.core.combat.Loadout;
import org.maiwithu.maicraft.core.task.combat.AttackCompanionTask;
import org.maiwithu.maicraft.core.task.combat.AttackTaskRecord;
import org.maiwithu.maicraft.task.TaskState;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import static org.maiwithu.maicraft.client.actor.CombatThreatsTest.check;

/** 通过真实射击状态机和原生动作端口计数，检查发射前瞄准与不射箭的取消。 */
public final class RangedShotTest {
    private static final Ballistics.Aim ALIGNED = new Ballistics.Aim(new Vec3(0, 1, 10), new Vec3(0, 0, 1), 5);
    private static final Ballistics.Aim MISALIGNED = new Ballistics.Aim(new Vec3(10, 1, 0), new Vec3(1, 0, 0), 5);
    private static final Class<?> SHOT = type();

    public static void main(String[] args) throws Exception {
        bowSurvivesNativeReleaseCheck();
        cancelledTaskCannotReleaseChargedBow();
        deferredCancellationCannotTouchLaterUse();
        waitingForRangeKeepsNativeDraw();
        foreignUseIsNotBorrowedOrCancelled();
        chargedWaitsAndFiresOnce();
        loadsBeforeFiring();
        cancelBow(false); cancelBow(true);
        abortAtSpentMutationBoundary();
        System.out.println("RangedShotTest: 原生持用续订、蓄力时钟、射程等待、装填发射和取消通过");
    }

    private static void chargedWaitsAndFiresOnce() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var bow = charged(); f.h.inventory.setItem(0, bow);
            f.h.mode.itemUse = p -> bow.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.EMPTY);
            var shot = shot(f.h.player, true);
            for (int i = 0; i < 5; i++) { tick(shot, MISALIGNED); nativeNext(f); }
            check(f.h.mode.items == 0, "a loaded crossbow must not use/fire before alignment");
            for (int i = 0; i < 4 && !tick(shot, ALIGNED); i++) nativeNext(f);
            check(fired(shot) && f.h.mode.items == 1, "alignment produces one confirmed shot, with no extra load click");
        }
    }

    private static void loadsBeforeFiring() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var bow = new ItemStack(Items.CROSSBOW); f.h.inventory.setItem(0, bow);
            f.h.mode.itemUse = p -> {
                if (CrossbowItem.isCharged(bow)) bow.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.EMPTY);
                else p.startUsingItem(InteractionHand.MAIN_HAND);
            };
            f.h.mode.itemRelease = p -> {
                check(p.getTicksUsingItem() >= CrossbowItem.getChargeDuration(bow, p), "不能把提前松开的弩伪装成已装填");
                bow.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(new ItemStack(Items.ARROW)));
            };
            var shot = shot(f.h.player, true);
            for (int i = 0; i < 100 && !tick(shot, ALIGNED); i++) nativeNext(f);
            check(fired(shot) && f.h.mode.items == 2 && f.h.mode.releases == 1,
                    "an empty crossbow must load, release loading, then fire exactly once");
        }
    }

    private static void cancelBow(boolean explicit) throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            f.h.inventory.setItem(0, new ItemStack(Items.BOW));
            f.h.mode.itemUse = p -> p.startUsingItem(InteractionHand.MAIN_HAND);
            var shot = shot(f.h.player, false);
            if (explicit) {
                tick(shot, MISALIGNED); nativeNext(f); call(shot, "abort");
            } else {
                for (int i = 0; i < 60 && !tick(shot, MISALIGNED); i++) nativeNext(f);
            }
            check(!fired(shot) && f.h.mode.releases == 0 && f.h.mode.items == 1,
                    "timeout or interruption must cancel without a release packet or firing another use");
            check(!f.h.player.isUsingItem() && f.h.inventory.selected != 0,
                    "native main-hand cancellation switches away from the charged bow");
            check(f.h.h.connection.packets.stream().anyMatch(p -> p instanceof ServerboundSetCarriedItemPacket),
                    "cancellation must reach the server's carried-item path");
        }
    }

    private static void abortAtSpentMutationBoundary() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            f.h.inventory.setItem(0, new ItemStack(Items.BOW));
            f.h.mode.itemUse = p -> p.startUsingItem(InteractionHand.MAIN_HAND);
            var shot = shot(f.h.player, false);
            // 拉弓的 useItem 已占用本刻操作名额；同刻任务失败走到 abort 时名额不可用。
            tick(shot, MISALIGNED);
            call(shot, "abort");
            check(!fired(shot) && f.h.mode.releases == 0 && f.h.inventory.selected == 0,
                    "abort at a spent mutation must neither throw nor release or switch the slot");
            check(f.h.h.connection.packets.stream().noneMatch(
                            p -> p instanceof ServerboundSetCarriedItemPacket),
                    "no carried-item packet may be sent when this tick's mutation budget is already spent");
            // 下一刻只推进正常运行时，不再要求已被任务丢弃的射击对象重试 abort。
            nativeNext(f);
            check(!f.h.player.isUsingItem() && f.h.inventory.selected == 1
                            && f.h.h.connection.packets.stream().anyMatch(
                            p -> p instanceof ServerboundSetCarriedItemPacket),
                    "a later abort with a free mutation still cancels through the carried-item path");
        }
    }

    private static ItemStack charged() {
        var result = new ItemStack(Items.CROSSBOW);
        result.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(new ItemStack(Items.ARROW)));
        return result;
    }

    private static void bowSurvivesNativeReleaseCheck() throws Exception {
        // 模拟原版每刻的松手检查并推进真正的持用倒计时；只证明蓄力和释放请求，不伪造箭实体或目标伤害。
        try (var f = new CombatThreatsTest.Fixture()) {
            f.h.inventory.setItem(0, new ItemStack(Items.BOW));
            f.h.mode.itemUse = p -> p.startUsingItem(InteractionHand.MAIN_HAND);
            int[] releasedAfter = {-1}; f.h.mode.itemRelease = p -> releasedAfter[0] = p.getTicksUsingItem();
            var shot = shot(f.h.player, false);
            for (int i = 0; i < 60 && !tick(shot, ALIGNED); i++) nativeNext(f);
            check(fired(shot) && releasedAfter[0] >= 15 && f.h.mode.items == 1 && f.h.mode.releases == 1,
                    "拉弓必须通过原版松手检查，达到蓄力时间后只提交一次释放");
            check(!ItemUseInputLease.project(f.h.h.minecraft, false), "释放后不再保持使用键或自动开始下一箭");
        }
    }

    private static void nativeNext(CombatThreatsTest.Fixture f) throws Exception {
        // 与 handleKeybinds 保持同一松手条件；旧测试仅保留 isUsingItem=true，会掩盖真实客户端的提前释放。
        if (f.h.player.isUsingItem() && !ItemUseInputLease.project(f.h.h.minecraft, false)) f.h.mode.releaseUsingItem(f.h.player);
        if (f.h.player.isUsingItem()) {
            var usingTick = LivingEntity.class.getDeclaredMethod("updateUsingItem", ItemStack.class); usingTick.setAccessible(true);
            usingTick.invoke(f.h.player, f.h.player.getUseItem());
        }
        f.h.nextTick(); f.h.h.actions.advance(f.h.h.context);
    }

    private static void cancelledTaskCannotReleaseChargedBow() throws Exception {
        // 已拉满、操作名额已用、任务只清理一次；此后不再调用 shot.abort，只走正常原版输入检查与端口 advance。
        try (var f = new CombatThreatsTest.Fixture()) {
            cancelChargedTaskOnce(f);
            check(f.h.player.isUsingItem() && f.h.inventory.selected == 0, "预算耗尽的当前刻不能额外切槽");
            nativeNext(f);
            check(f.h.mode.releases == 0 && !f.h.player.isUsingItem() && f.h.inventory.selected == 1,
                    "运行时必须切槽取消，不能让原版松手路径把已蓄力的箭射出");
            nativeNext(f);
            check(f.h.mode.items == 1 && f.h.mode.releases == 0 && f.h.h.connection.packets.stream()
                    .filter(p -> p instanceof ServerboundSetCarriedItemPacket).count() == 1,
                    "任务丢弃射击状态后仍能一次性完成取消，不重放使用或重复切槽");
        }
    }

    private static void cancelChargedTaskOnce(CombatThreatsTest.Fixture f) throws Exception {
        f.h.inventory.setItem(0, new ItemStack(Items.BOW));
        f.h.mode.itemUse = p -> p.startUsingItem(InteractionHand.MAIN_HAND);
        var shot = shot(f.h.player, false); tick(shot, MISALIGNED);
        for (int i = 0; i < 20; i++) { nativeNext(f); tick(shot, MISALIGNED); }
        check(f.h.player.getTicksUsingItem() >= 20, "确实已经原生蓄力，不能只测试起手后无箭可射的取消");
        var task = new AttackCompanionTask(f.h.player, new AttackTaskRecord("cancel-held-bow", 1000, List.of(), true));
        task.start(f.h.player); ActorControlTestHarness.field(AttackCompanionTask.class, "shot").set(task, shot);
        f.h.h.context.claimMutation(); task.result(TaskState.CANCELLED);
        check(ActorControlTestHarness.field(AttackCompanionTask.class, "shot").get(task) == null, "真实任务已经丢弃射击状态");
    }

    private static void deferredCancellationCannotTouchLaterUse() throws Exception {
        // 旧任务已经排队取消后，新回执接管进食；原生端口不能把旧弓的切槽债应用到面包上。
        try (var f = new CombatThreatsTest.Fixture()) {
            cancelChargedTaskOnce(f); f.h.player.stopUsingItem(); f.h.nextTick();
            f.h.inventory.setItem(0, new ItemStack(Items.BREAD));
            var other = Interaction.useInAir(f.h.player, InteractionHand.MAIN_HAND, Interaction.Timing.hold()); other.tick();
            nativeNext(f);
            check(f.h.player.isUsingItem() && f.h.inventory.selected == 0 && f.h.mode.releases == 0
                    && f.h.h.connection.packets.stream().noneMatch(p -> p instanceof ServerboundSetCarriedItemPacket),
                    "后来者自己的使用回执和租约保持有效，不被旧取消切槽");
            other.stop();
        }
        // 同一槽位同一把弓重新起手，倒计时回升也表示另一次持用，不能只按物品相同就取消。
        try (var f = new CombatThreatsTest.Fixture()) {
            cancelChargedTaskOnce(f); f.h.player.stopUsingItem(); f.h.player.startUsingItem(InteractionHand.MAIN_HAND);
            f.h.nextTick(); f.h.h.actions.advance(f.h.h.context);
            check(f.h.player.isUsingItem() && f.h.inventory.selected == 0
                    && f.h.h.connection.packets.stream().noneMatch(p -> p instanceof ServerboundSetCarriedItemPacket),
                    "同物品的新持用也不能继承旧取消");
        }
        // 控制权交回玩家后，运行时只丢弃旧取消，不能再切换玩家当前选择的武器。
        try (var f = new CombatThreatsTest.Fixture()) {
            cancelChargedTaskOnce(f); f.h.h.actions.revokeForBoundary("test human handoff");
            f.h.nextTick(); f.h.h.actions.advance(f.h.h.context);
            check(!ItemUseInputLease.project(f.h.h.minecraft, false) && f.h.inventory.selected == 0
                    && f.h.h.connection.packets.stream().noneMatch(p -> p instanceof ServerboundSetCarriedItemPacket),
                    "身体边界撤销持用和取消责任，旧任务不干预新操作者");
        }
    }

    private static void waitingForRangeKeepsNativeDraw() throws Exception {
        // 等待射程和操作名额时执行真实任务的早退分支，持用仍由原版倒计时推进，不能偷偷重启右键。
        try (var f = new CombatThreatsTest.Fixture()) {
            f.h.inventory.setItem(0, new ItemStack(Items.BOW)); f.h.player.getAbilities().instabuild = true;
            f.h.mode.itemUse = p -> p.startUsingItem(InteractionHand.MAIN_HAND);
            var foe = f.mob(11, 20);
            var task = new AttackCompanionTask(f.h.player, new AttackTaskRecord("ranged-hold", 1000, List.of(11), false));
            task.start(f.h.player);
            var shot = shot(f.h.player, false); tick(shot, MISALIGNED);
            ActorControlTestHarness.field(AttackCompanionTask.class, "shot").set(task, shot);
            ActorControlTestHarness.field(AttackCompanionTask.class, "target").set(task, foe);
            var shoot = AttackCompanionTask.class.getDeclaredMethod("shootAt", Loadout.class); shoot.setAccessible(true);
            for (int i = 0; i < 18; i++) {
                nativeNext(f);
                if (i == 2) f.h.h.context.claimMutation();
                shoot.invoke(task, Loadout.forTarget(f.h.player, foe));
            }
            check(f.h.player.isUsingItem() && f.h.mode.items == 1 && f.h.mode.releases == 0, "早退分支仍保持原来那一次拉弓");
            var velocity = SHOT.getDeclaredMethod("projectileVelocity", double.class, double.class); velocity.setAccessible(true);
            double actual = (Double) velocity.invoke(shot, 3.0, 3.15);
            check(Math.abs(actual - BowItem.getPowerForTime(f.h.player.getTicksUsingItem()) * 3.0) < 1e-5,
                    "弹道速度使用原生蓄力读数，不使用被射程等待冻结的调用次数");
            f.h.nextTick(); task.result(TaskState.CANCELLED);
            check(!ItemUseInputLease.project(f.h.h.minecraft, false), "任务结束后撤销自己的持用投影");
        }
    }

    private static void foreignUseIsNotBorrowedOrCancelled() throws Exception {
        // 后来的持用有自己的原生回执和按键租约；旧弓流程不能把它当成继续蓄力，更不能替它松手。
        try (var f = new CombatThreatsTest.Fixture()) {
            f.h.inventory.setItem(0, new ItemStack(Items.BOW));
            f.h.mode.itemUse = p -> p.startUsingItem(InteractionHand.MAIN_HAND);
            var shot = shot(f.h.player, false); tick(shot, MISALIGNED); nativeNext(f); tick(shot, MISALIGNED);
            f.h.player.stopUsingItem(); ItemUseInputLease.release(shot); f.h.nextTick();
            f.h.inventory.setItem(0, new ItemStack(Items.BREAD));
            var other = Interaction.useInAir(f.h.player, InteractionHand.MAIN_HAND, Interaction.Timing.hold()); other.tick();
            check(tick(shot, MISALIGNED), "持用所有者改变后原射击结束"); call(shot, "abort");
            check(!fired(shot) && f.h.player.isUsingItem() && f.h.mode.items == 2 && f.h.mode.releases == 0
                    && ItemUseInputLease.project(f.h.h.minecraft, false), "不重发使用、不取消后来者、不冒称发射成功");
            var evidence = (Map<?, ?>) call(shot, "evidence");
            check("held_use_ownership_changed".equals(evidence.get("failure")), "保留真实持用失败阶段供实机检查");
            nativeNext(f); other.stop();
        }
    }
    private static Object shot(LocalPlayer player, boolean crossbow) throws Exception {
        // 无构造器夹具补齐真实客户端标志，避免原生弩持用刻误走服务端音效分支；倒计时仍由原版推进。
        ActorControlTestHarness.field(Level.class, "isClientSide").setBoolean(player.level(), true);
        var constructor = SHOT.getDeclaredConstructor(LocalPlayer.class, boolean.class);
        constructor.setAccessible(true); return constructor.newInstance(player, crossbow);
    }
    private static boolean tick(Object shot, Ballistics.Aim aim) throws Exception {
        Method method = SHOT.getDeclaredMethod("tick", Ballistics.Aim.class, Entity.class);
        method.setAccessible(true); return (Boolean) method.invoke(shot, aim, null);
    }
    private static Object call(Object shot, String name) throws Exception {
        var method = SHOT.getDeclaredMethod(name); method.setAccessible(true); return method.invoke(shot);
    }
    private static boolean fired(Object shot) throws Exception { return (Boolean) call(shot, "fired"); }
    private static Class<?> type() {
        try { return Class.forName("org.maiwithu.maicraft.core.task.combat.RangedShot"); }
        catch (ClassNotFoundException failure) { throw new AssertionError(failure); }
    }
}
