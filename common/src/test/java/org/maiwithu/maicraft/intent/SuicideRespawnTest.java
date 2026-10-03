// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.Executor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.runtime.GameplayAttentionMonitor;
import org.maiwithu.maicraft.core.task.suicide.SuicideTaskTest;
import org.maiwithu.maicraft.intent.persistence.IntentStateStore;
import org.maiwithu.maicraft.intent.persistence.IntentStateCodec;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;
import org.maiwithu.maicraft.task.TaskState;

/** 沿死亡监视器、调度槽、检查点和原生重生包完整回放，确保主动死亡既不丢结果也不重复请求重生。 */
public final class SuicideRespawnTest {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        IntentRuntime runtime = IntentRuntime.get();
        var saved = new LinkedHashMap<Field, Object>();
        for (String name : List.of("stateStore", "stateIdentity", "bodyAttached", "dirty", "nextSaveNanos")) {
            Field field = field(IntentRuntime.class, name); saved.put(field, field.get(runtime));
        }
        var constructor = IntentStateStore.class.getDeclaredConstructor(Executor.class); constructor.setAccessible(true);
        var store = constructor.newInstance((Executor) Runnable::run);
        var identity = new StateIdentity("d".repeat(64), Files.createTempDirectory("suicide-respawn-"));
        field(IntentRuntime.class, "stateStore").set(runtime, store);
        field(IntentRuntime.class, "stateIdentity").set(runtime, identity);
        field(IntentRuntime.class, "bodyAttached").set(runtime, true);
        var tasks = (Map<UUID, IntentTaskRecord>) field(IntentRuntime.class, "tasks").get(runtime);
        var goal = new Goal("maicraft:suicide", "在死亡不掉落时返回重生点", null,
                "{\"method\":\"lava\",\"search_radius\":4,\"keep_inventory_confirmed\":true}", "{}", List.of(), List.of());
        var record = new IntentTaskRecord(UUID.randomUUID(), null, goal, identity.key()); tasks.put(record.externalId(), record);
        GameplayAttentionMonitor.reset();
        var world = new InteractionWorldTestHarness();
        try {
            SuicideTaskTest.prepare(world);
            // 死亡监视器同时读取天气；补齐测试维度的自然光条件，继续走正式观察入口而非绕过死亡分支。
            var dimension = new DimensionType(OptionalLong.empty(), true, false, false, true, 1, true, false, 0, 16, 16,
                    BlockTags.INFINIBURN_OVERWORLD, ResourceLocation.parse("minecraft:overworld"), 0,
                    new DimensionType.MonsterSettings(false, false, ConstantInt.of(0), 0));
            field(Level.class, "dimensionTypeRegistration").set(world.level, Holder.direct(dimension));
            // 只模拟网络仍连接；重生包仍由 LocalPlayer.respawn 真实生成并走夹具的原生发包记录器。
            var listener = Minecraft.getInstance().getConnection();
            field(ClientPacketListener.class, "connection").set(listener, new Connection(PacketFlow.CLIENTBOUND) {
                @Override public boolean isConnected() { return true; }
            });
            var packets = (List<Packet<?>>) field(listener.getClass(), "packets").get(listener);
            CompanionTickDispatcher.submitCurrent(world.player, record);
            for (int tick = 0; tick < 10; tick++) { world.nextTick(); CompanionTickDispatcher.tick(world.player); }
            check(record.getState() == TaskState.RUNNING, "原生死亡前任务仍在执行");
            world.player.setHealth(0); GameplayAttentionMonitor.tick(world.player);
            check(record.getState() == TaskState.SUCCESS && record.stepIndex() == 1
                    && CompanionTickDispatcher.current() == null, "死亡观察应完成寻死并释放任务槽");
            check(record.getResult().toJson().contains("death_observed"), "完成通知必须保留死亡证据");
            check(respawns(packets) == 1, "已完成的寻死仍应发送一次原生重生请求");
            String checkpoint = store.load(identity).root().toString();
            check(checkpoint.contains("death_observed") && checkpoint.contains("maicraft:suicide"), "重生交接应保存实际死亡结果");
            var persisted = IntentStateCodec.decode(store.load(identity).root()).tasks().getFirst();
            check(persisted.stepIndex() == 1 && persisted.terminal() != null, "检查点恢复不能再次执行已完成的寻死步骤");
            GameplayAttentionMonitor.tick(world.player);
            check(respawns(packets) == 1, "仍在死亡界面时不能重复发包");
            world.player.setHealth(20); GameplayAttentionMonitor.tick(world.player);
            GameplayAttentionMonitor.afterSemanticBind(world.player);
            check(!GameplayAttentionMonitor.blocksAutomation(world.player) && record.stepIndex() == 1,
                    "观察到新生命后解除死亡锁，但不能倒退寻死步骤");
        } finally {
            try { CompanionTickDispatcher.bodyGone(); } finally { world.close(); }
            GameplayAttentionMonitor.reset(); tasks.remove(record.externalId());
            for (var entry : saved.entrySet()) entry.getKey().set(runtime, entry.getValue());
        }
        System.out.println("SuicideRespawnTest: passed");
    }

    private static long respawns(List<Packet<?>> packets) {
        return packets.stream().filter(packet -> packet instanceof ServerboundClientCommandPacket command
                && command.getAction() == ServerboundClientCommandPacket.Action.PERFORM_RESPAWN).count();
    }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { Field field = current.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
