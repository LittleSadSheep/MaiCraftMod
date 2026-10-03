// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.suicide;

import java.lang.reflect.Field;
import java.util.UUID;
import com.mojang.authlib.GameProfile;
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.BodyControlPort;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;

/** 原生按键在受控夹具中回放，血量变化由测试世界明确注入，执行器本身不能扣血或宣称已重生。 */
public final class SuicideTaskTest {
    public static void main(String[] args) throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            prepare(world);
            var denied = task(world, false, 10);
            check(denied.tick(world.player) == TaskState.FAILED && !denied.suppressesSurvivalReflexes(), "规则未确认时应停止并恢复保护");
            check(!denied.result(TaskState.FAILED).data().get("death_observed").equals(true), "拒绝不能伪造死亡");
            world.player.getAbilities().instabuild = true;
            mode(GameType.CREATIVE);
            var creative = task(world, true, 10);
            check(creative.tick(world.player) == TaskState.FAILED, "创造身体不能被当作可正常寻死的生存身体");
            world.player.getAbilities().instabuild = false;
            mode(GameType.SPECTATOR);
            check(task(world, true, 10).tick(world.player) == TaskState.FAILED, "旁观身体不能执行寻死");
            mode(GameType.SURVIVAL);
        }
        try (var world = new InteractionWorldTestHarness()) {
            prepare(world);
            var suicide = task(world, true, 10);
            begin(suicide, world);
            check(movement().forward() > 0 && !movement().sneaking(), "主动走进岩浆时不能潜行卡在岸边");
            check(world.player.getHealth() == 20 && world.blockUses() == 0 && world.itemUses() == 0,
                    "寻死必须依赖原生环境伤害，不能直接扣血或改物品");
            suicide.stop(world.player, Task.StopReason.PREEMPTED);
            check(movement().equals(BodyControlPort.Movement.STOPPED), "暂停应立即松开危险移动按键");
            world.nextTick(); suicide.tick(world.player);
            world.player.setHealth(12); world.nextTick(); suicide.tick(world.player);
            world.player.setHealth(0);
            check(suicide.observeDeath(world.player) && !suicide.observeDeath(world.player), "同一死亡只能结算一次");
            var result = suicide.result(TaskState.SUCCESS);
            check(result.success() && result.data().get("death_observed").equals(true)
                    && result.data().get("respawn_observed").equals(false), "死亡成功与尚未确认的重生必须分开");
            check(((Number) result.data().get("health_lost")).floatValue() == 20, "最后致死伤害也应进入结果");
            check(!suicide.suppressesSurvivalReflexes() && movement().equals(BodyControlPort.Movement.STOPPED), "死亡后恢复保护并归还身体");
        }
        try (var world = new InteractionWorldTestHarness()) {
            prepare(world);
            var timeout = task(world, true, 10);
            TaskState state = TaskState.RUNNING;
            for (int tick = 0; tick <= 201 && state == TaskState.RUNNING; tick++) { world.nextTick(); state = timeout.tick(world.player); }
            check(state == TaskState.TIMEOUT && timeout.result(state).timedOut(), "只走动却没有死亡证据时必须按预算超时");
            check(!timeout.suppressesSurvivalReflexes() && movement().equals(BodyControlPort.Movement.STOPPED), "超时不能泄漏保护豁免或按键");
            var cancelled = task(world, true, 10); begin(cancelled, world);
            cancelled.stop(world.player, Task.StopReason.REPLACED); world.player.setHealth(0);
            check(!cancelled.observeDeath(world.player) && !cancelled.suppressesSurvivalReflexes(), "取消后的后续死亡不能复活旧寻死任务");
        }
        try (var world = new InteractionWorldTestHarness()) {
            // 高台迈出后模拟一次原生落地但未致死；执行器只保留坠落事实，不把“已经跳了”冒称成功。
            prepare(world); world.position(new Vec3(8.5, 10, 8.5));
            world.set(new BlockPos(8, 9, 8), Blocks.STONE.defaultBlockState());
            var fall = new SuicideTask(world.player, new SuicideTaskRecord("fall-test", new SuicideRequest("fall", 4, 10, true)));
            begin(fall, world);
            for (int tick = 0; tick < 11; tick++) { world.nextTick(); fall.tick(world.player); }
            world.position(new Vec3(8.5, 1, 7.5)); world.player.setHealth(11); world.nextTick();
            check(fall.tick(world.player) == TaskState.RUNNING && fall.progress().get("death_observed").equals(false),
                    "从高处摔下但活着应继续寻找方式，不能记成死亡");
            check(fall.progress().get("attempts").toString().contains("still alive"), "存活落地应保留真实失败原因");
            fall.stop(world.player, Task.StopReason.REPLACED);
            check(world.itemUses() == 0 && world.blockUses() == 0, "主动坠落不能垫水、防摔或修改地形");
        }
        System.out.println("SuicideTaskTest: passed");
    }

    public static void prepare(InteractionWorldTestHarness world) throws Exception {
        // 给夹具补齐实际模式与原生窗口上下文，在同一岸边观察按键，不模拟游戏物理或自动伤害。
        var data = new ClientLevel.ClientLevelData(Difficulty.NORMAL, false, false);
        set(Level.class, world.level, "levelData", data);
        set(ClientLevel.class, world.level, "clientLevelData", data);
        set(LocalPlayer.class, world.player, "minecraft", Minecraft.getInstance());
        set(Entity.class, world.player, "fluidHeight", new Object2DoubleOpenHashMap<>());
        mode(GameType.SURVIVAL);
        world.position(new Vec3(8.5, 2, 8.5)); world.player.setOnGround(true);
        world.set(new BlockPos(8, 1, 8), Blocks.STONE.defaultBlockState());
        world.set(new BlockPos(9, 1, 8), Blocks.LAVA.defaultBlockState());
    }

    public static void begin(Task task, InteractionWorldTestHarness world) throws Exception {
        for (int tick = 0; tick < 10; tick++) {
            world.nextTick();
            check(task.tick(world.player) == TaskState.RUNNING, "活着时不能提前报告完成");
            if (task.progress().get("phase").equals("exposing_to_hazard")) return;
        }
        throw new AssertionError("未开始原生危险移动");
    }

    private static SuicideTask task(InteractionWorldTestHarness world, boolean confirmed, int seconds) {
        return new SuicideTask(world.player, new SuicideTaskRecord("test", new SuicideRequest("lava", 4, seconds, confirmed)));
    }

    private static void mode(GameType mode) throws Exception {
        // 原版本地玩家从玩家列表信息判断创造与旁观，不能只设置背包能力就假装已收到模式同步。
        var info = new PlayerInfo(new GameProfile(UUID.randomUUID(), "SuicideTest"), false);
        set(PlayerInfo.class, info, "gameMode", mode);
        set(AbstractClientPlayer.class, Minecraft.getInstance().player, "playerInfo", info);
        set(MultiPlayerGameMode.class, Minecraft.getInstance().gameMode, "localPlayerMode", mode);
    }

    private static BodyControlPort.Movement movement() throws Exception {
        var body = ClientRuntime.actor().body(); Field field = body.getClass().getDeclaredField("movement");
        field.setAccessible(true); return (BodyControlPort.Movement) field.get(body);
    }

    private static void set(Class<?> type, Object target, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
