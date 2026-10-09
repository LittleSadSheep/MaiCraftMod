// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.acquire.LiveCarryReads;
import org.maiwithu.maicraft.behavior.navigation.baritone.BaritoneInternals;
import org.maiwithu.maicraft.behavior.permission.ReadsCreatureSituation;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.survival.CombatMemory;
import org.maiwithu.maicraft.behavior.survival.CombatSenses;
import org.maiwithu.maicraft.behavior.survival.LiveCombatSenses;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.ChatChannel;
import org.maiwithu.maicraft.game.ChatLog;
import org.maiwithu.maicraft.game.ClientHooks;
import org.maiwithu.maicraft.game.player.InputDriver;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerControlBoundary;
import org.maiwithu.maicraft.game.serverlink.LinkTransport;
import org.maiwithu.maicraft.game.serverlink.ServerLinkSession;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.InMemoryGoalRunStore;
import org.maiwithu.maicraft.kernel.param.Params;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.interrupt.ControlLoop;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;


import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.travel.ClientTravelWorldView;

import com.google.gson.JsonObject;

/**
 * 总装的替身测试：能力清单能在没有任何真实游戏对象的情况下创建并登记；
 * 下达一个目标后控制循环每刻能推进，目标按能力自己的节奏走完。
 * 真实的端到端连通（LLM 一句话 → 角色动手）是实机验收，这里只保证装得上、推得动。
 */
class AssemblyTest {

    @TempDir
    Path tempDir;

    /** 一条空的发送通道：总装测试不连服务器，会话构造需要它存在而已。 */
    private static final LinkTransport NO_TRANSPORT = new LinkTransport() {
        @Override public boolean available() {
            return false;
        }

        @Override public void send(JsonObject envelope) {
            throw new IllegalStateException("总装测试不发信封");
        }
    };

    @Test
    void catalogRegistersEveryAssembledAbility() {
        AbilityRegistry registry = AbilityCatalog.create(deps());

        Set<String> registered = new java.util.LinkedHashSet<>();
        for (var module : registry.all()) {
            registered.add(module.spec().name());
        }
        // 存东西能力接上了：找容器、界面读数、整堆搬运与挖盖子都有实现方。
        assertEquals(Set.of("use", "eat", "equip", "drop", "obtain", "gather", "deposit",
                "fight", "follow", "wait", "travel", "chat"), registered,
                "清单里的能力要一个不少地登记上");
    }

    @Test
    void assignedGoalAdvancesThroughControlLoopEachTick() {
        var registry = AbilityCatalog.create(deps());
        // 手上没有生存需求（替身清单为空），控制循环只推进主任务。
        ControlLoop controlLoop = new ControlLoop(List.of());
        MainGoalSlot goals = new MainGoalSlot(controlLoop, registry,
                new InMemoryGoalRunStore(), deps().memory());
        // 等待目标：条件是"过了 0 秒"，开工即完成——推进路径走的是真实的任务与控制循环。
        // 目标写能力的完整 ID：目标推进器按 ID 查清单。
        goals.assign(Goal.of("maicraft:wait", null, Params.EMPTY));
        assertTrue(goals.running().isPresent(), "下达后目标推进器要在槽里");

        // 下达后的第一刻能力做决定、开出任务，随后一刻任务走完：两刻内给结果。
        TaskResult finished = null;
        for (int tick = 0; tick < 3 && finished == null; tick++) {
            ControlLoop.Decision decision = controlLoop.tick(new OfflineTick());
            ControlLoop.Decision.Advanced advanced =
                    assertInstanceOf(ControlLoop.Decision.Advanced.class, decision);
            finished = advanced.finished();
        }
        assertNotNull(finished, "零秒等待在几刻内就该走完");
        assertEquals(TaskResult.Status.DONE, finished.status(),
                "等待完成的结算要如实写清：" + finished.summary());
    }

    /** 总装依赖的一份：全部用离线可构造的实例，不碰任何真实游戏对象。 */
    private AbilityCatalog.Deps deps() {
        Supplier<PlayerContext> nobody = () -> null;
        WorldMemory memory = new WorldMemory(new DocumentStore(tempDir.resolve("state.sqlite")), testKey());
        Scene scene = new Scene(memory);
        CombatSenses senses = new LiveCombatSenses(new CombatMemory());
        ChatChannel chat = new ChatChannel(nobody);
        ClientHooks.registerChatLog(new ChatLog());
        // 交互动作入口允许没有按住使用键投影；生存需求共用的挖掘走原生交互，测试里给空壳。
        return new AbilityCatalog.Deps(
                nobody,
                new Interactions(null),
                new BaritoneInternals(),
                new BaritoneInternals(),
                new BlockScanService(),
                scene, memory,
                new ServerLinkSession(NO_TRANSPORT),
                "00000000-0000-0000-0000-000000000000",
                (ReadsCreatureSituation) entityId -> Optional.empty(),
                senses,
                PlayerViews.backpack(nobody),
                LiveCarryReads.offhand(nobody),
                LiveCarryReads.itemTags(),
                LiveCarryReads.characterPosition(nobody),
                LiveCarryReads.itemRegistry(),
                LiveCarryReads.toolRequirements(nobody),
                PlayerViews.hunger(nobody),
                PlayerViews.foods(nobody),
                PlayerViews.equipment(nobody),
                PlayerViews.effects(nobody),
                PlayerViews.gearFit(nobody),
                // 按住使用键投影的替身：进食的续期一律不续，任务按"按住到期"如实收场。
                new org.maiwithu.maicraft.behavior.interaction.UseKeyProjection() {
                    @Override public boolean renew(Object owner, PlayerContext context,
                            org.maiwithu.maicraft.game.interaction.PendingInteraction pending,
                            net.minecraft.world.InteractionHand hand, net.minecraft.world.item.ItemStack before) {
                        return false;
                    }

                    @Override public void release(Object owner) {
                    }
                },
                () -> null,
                new ClientTravelWorldView(nobody),
                progress -> { },
                chat,
                () -> 0,
                new InputDriver(new PlayerControlBoundary()));
    }

    /** 测试专用的世界身份编号：任意一个合法的 SHA-256 形状。 */
    private static String testKey() {
        return "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    }

    /** 离线的一刻：只有游戏刻号，没有角色；等待任务按"角色不在"如实处理。 */
    private record OfflineTick() implements TickContext {
        @Override public long gameTick() {
            return 0;
        }

        @Override public PlayerContext player() {
            return null;
        }
    }
}
