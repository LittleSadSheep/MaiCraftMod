package org.maiwithu.maicraft.client.actor;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Drowned;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.task.chain.BreathChain;
import org.maiwithu.maicraft.core.task.chain.MobDefenseChain;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskSelector;
import static org.maiwithu.maicraft.client.actor.CombatThreatsTest.check;

/** 真正的溺尸伤害包 -> 换气赢得调度 -> 原生反击；输入仍必须沿原上浮或低顶逃生路线。 */
public final class BreathDefenseTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        defend(false); defend(true);
        System.out.println("BreathDefenseTest: 溺尸反击、换气优先、上浮与低顶横游通过");
    }

    private static void defend(boolean roof) throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var w = f.h;
            WetPlayer player = wetPlayer(w);
            w.position(new Vec3(5.5, 1, 3.5)); player.setAirSupply(40);
            if (roof) w.inventory.setItem(0, new ItemStack(Items.IRON_SWORD)); // 同时覆盖空手脱险与手持近战武器。
            for (int x = 1; x < 15; x++) for (int z = 1; z < 15; z++) {
                for (int y = 1; y <= 2; y++) w.set(new BlockPos(x, y, z), Blocks.WATER.defaultBlockState());
                if (roof && x < 8) w.set(new BlockPos(x, 3, z), Blocks.STONE.defaultBlockState());
            }
            var drowned = f.mob(TestDrowned.class, EntityType.DROWNED, 11, 4);
            var bystander = f.mob(12, 7.5); // 逃生口方向的旁观者不能因为正在水里就变成可攻击目标。
            var breath = new BreathChain(); var defense = new MobDefenseChain();
            w.nextTick();
            check(TaskSelector.select(List.of(breath, defense), null, null, List.of(), player) == breath,
                    "低氧时换气仍赢得身体调度");
            for (int i = 0; i < 10; i++) { breath.tick(player); w.nextTick(); }
            check(w.mode.attacks == 0, "没有伤害或明确攻击目标时，水中相邻生物不触发攻击");
            f.hit(drowned, drowned);
            check(defense.canRun(player), "真实溺尸伤害应能触发普通自卫检测");
            // 换气抢占时旧工作可能还在等服务器确认；救命的移动继续，手部不能抢占或重放旧点击。
            boolean[] confirmed = {false};
            w.h.actions.submitControlProtocol(w.h.context, "prior work", () -> {},
                    c -> confirmed[0] ? NativeConfirmation.Verdict.APPLIED : NativeConfirmation.Verdict.PENDING, 20);
            for (int i = 0; i < 3; i++) { breath.tick(player); w.nextTick(); w.h.actions.advance(w.h.context); }
            check(w.mode.attacks == 0, "旧操作未结清时继续换气，不抢占原生动作槽");
            confirmed[0] = true;
            for (int i = 0; i < 2; i++) { w.nextTick(); w.h.actions.advance(w.h.context); }
            // 即使急着脱离溺尸，也不能绕过原生攻击充能不断发送点击。
            ActorControlTestHarness.field(Player.class, "attackStrengthTicker").setInt(player, 0);
            for (int i = 0; i < 2; i++) { breath.tick(player); w.nextTick(); }
            check(w.mode.attacks == 0, "冷却尚未恢复时只游泳，不强行出刀");
            ActorControlTestHarness.field(Player.class, "attackStrengthTicker").setInt(player, 100);
            boolean moving = false;
            for (int i = 0; i < 100 && w.mode.attacks == 0; i++) {
                check(TaskSelector.select(List.of(breath, defense), null, null, List.of(), player) == breath,
                        "反击不需要降低换气优先级或切走身体持有者");
                // 无渲染夹具让真实视角对准溺尸，发刀仍须通过生产射线、距离和原生端口。
                Vec3 aim = drowned.getBoundingBox().getCenter().subtract(player.getEyePosition());
                player.setYRot((float) Math.toDegrees(Math.atan2(-aim.x, aim.z)));
                player.setXRot((float) -Math.toDegrees(Math.atan2(aim.y, aim.horizontalDistance())));
                breath.tick(player);
                var command = (BodyControlPort.Movement) ActorControlTestHarness.field(DefaultBodyControlPort.class, "movement").get(w.h.body);
                if (roof) moving |= Math.abs(command.forward()) + Math.abs(command.strafe()) > .5 && !command.jumping();
                else {
                    moving |= command.jumping();
                    if (w.mode.attacks > 0) check(command.jumping(), "实际出刀这一刻不能清掉开阔水柱的上浮输入");
                }
                if (w.mode.attacks > 0 && roof) {
                    check(Math.abs(command.forward()) + Math.abs(command.strafe()) > .5 && !command.jumping(),
                            "实际出刀这一刻仍须沿低顶通道横游，不能停步或顶着天花板上浮");
                    check((Float) ActorControlTestHarness.field(DefaultBodyControlPort.class, "targetYaw").get(w.h.body) > 0,
                            "东侧逃生路线不能覆盖西侧溺尸的攻击瞄准");
                }
                w.nextTick();
            }
            check(w.mode.attacks == 1, "换气占用身体时仍应对已确认的溺尸提交一次原生攻击");
            check(!roof || moving, "低顶场景确实执行了横向逃生");
            // 回执等待期间不重复挥刀；目标尚未受伤时不能靠测试直接报成功。
            for (int i = 0; i < 3; i++) { breath.tick(player); w.nextTick(); }
            check(w.mode.attacks == 1, "等待攻击确认期间不重放原生点击");
            // 氧气恢复后把整场自卫交回普通战斗，不能让换气子动作继续占住身体或假报敌人已击败。
            player.eyesWet = false; player.setAirSupply(300);
            check(TaskSelector.select(List.of(breath, defense), null, null, List.of(), player) == defense,
                    "空气补满后，仍存活的攻击者由普通自卫继续处理");
            // 覆盖正常交接与死亡后入口已撤销的交接，不能在清理阶段再申请旧身体的动作上下文。
            if (roof) ActorControlTestHarness.field(ClientActorBoundary.class, "activeContext").set(w.actor, null);
            breath.stop(player, roof ? Task.StopReason.BODY_GONE : Task.StopReason.PREEMPTED);
            check(w.level.entities.containsKey(bystander.getId()), "旁观者始终保留");
        }
    }

    private static WetPlayer wetPlayer(InteractionWorldTestHarness w) throws Exception {
        // 仅替换可控浸水观测，保留现有客户端、伤害数据和身体端口；不声称模拟原生水下物理。
        var player = w.h.allocate(WetPlayer.class);
        for (Class<?> type = LocalPlayer.class; type != Object.class; type = type.getSuperclass())
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true); field.set(player, field.get(w.player));
            }
        w.h.body.bodyReplaced(player, true); player.input = new Input(); w.h.body.fulfillAutomationRequest(player);
        ActorControlTestHarness.field(ActorControlTestHarness.class, "player").set(w.h, player);
        ActorControlTestHarness.field(InteractionWorldTestHarness.class, "player").set(w, player);
        w.h.minecraft.player = player;
        ActorControlTestHarness.field(ClientActorBoundary.class, "observedPlayer").set(w.h.actor, player);
        ActorControlTestHarness.field(ClientActorBoundary.class, "observedPlayer").set(w.actor, player);
        player.eyesWet = true;
        return player;
    }

    private static final class WetPlayer extends LocalPlayer {
        boolean eyesWet;
        private WetPlayer() { super(null, null, null, null, null, false, false); }
        @Override public boolean isInWater() { return true; }
        @Override public boolean isEyeInFluid(TagKey<Fluid> tag) { return eyesWet; }
        @Override public boolean hasEffect(Holder<MobEffect> effect) { return false; }
        @Override public void setSprinting(boolean value) { /* 保留身体输入检查，不发送真实移动网络。 */ }
    }
    private static final class TestDrowned extends Drowned {
        private TestDrowned() { super(EntityType.DROWNED, null); }
        @Override public float getHealth() { return 20; }
    }
}
