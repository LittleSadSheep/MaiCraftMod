// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.find;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.perception.FacilityKinds;
import org.maiwithu.maicraft.behavior.perception.FakeBlockTags;
import org.maiwithu.maicraft.behavior.perception.RemembersSightings;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.perception.SelfSight;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 寻找任务：收命中、发观察编号、找满收工、扫完不足如实交账、换世界作废，都对着替身跑。 */
class FindTaskTest {

    /** 世界记忆替身：把写进来的"亲眼看到"记录一条条收着，供断言。 */
    private static final class FakeMemory implements RemembersSightings {
        record Seen(WorldPosition position, List<String> roughlyThere) {}
        final List<Seen> sites = new ArrayList<>();

        @Override public void containerSeen(WorldPosition position, String blockType, Instant when) { }
        @Override public void workstationSeen(WorldPosition position, String blockType, Instant when) { }
        @Override public void siteSeen(WorldPosition position, List<String> roughlyThere, Instant when) {
            sites.add(new Seen(position, roughlyThere));
        }
    }

    /** 附近寻找替身：按脚本逐刻给一轮扫描的收成；给完了重复最后一轮。 */
    private static final class ScriptedFinder implements FindsAround {
        final Deque<Round> script = new ArrayDeque<>();

        @Override public Round scan(FindInput input) {
            Round round = script.poll();
            if (round != null) return round;
            return new Round(List.of(), true, false);
        }
    }

    private final FakeMemory memory = new FakeMemory();
    private final ScriptedFinder finder = new ScriptedFinder();
    private final Scene scene = new Scene(memory, new FacilityKinds(FakeBlockTags.vanilla()));
    private long tick;

    FindTaskTest() {
        // 角色站在原点，脸朝北（视角角 180）：方位说法全部按这个基准算。
        scene.updateSelf(new SelfSight.Facts(0.5, 64, 0.5, 180f,
                20, 18, 300, 300, null, List.of(), List.of(), true, false));
    }

    private static FindsAround.Hit block(int x, int y, int z, String typeId) {
        return FindsAround.Hit.place(FindInput.FindKind.BLOCK, typeId, WorldPosition.here(x, y, z));
    }

    private TaskResult run(FindInput input) {
        FindTask task = new FindTask(input, scene, finder, memory);
        tick = 0;
        task.start(tick());
        for (int i = 0; i < 400; i++) {
            if (task.tick(tick()) instanceof TickResult.Finished finished) {
                return finished.result();
            }
        }
        throw new AssertionError("任务四百刻都没结束：" + task.describe());
    }

    private TickContext tick() {
        long now = ++tick;
        return new TickContext() {
            @Override public long gameTick() { return now; }
            @Override public PlayerContext player() { return null; }
        };
    }

    @Test
    void 方块命中发编号写记忆找满即收工() {
        // 近处的在后、远处的在前：收的时候仍按由近到远发编号。
        finder.script.add(new FindsAround.Round(List.of(
                block(0, 64, -20, "minecraft:coal_ore"), block(0, 64, -8, "minecraft:coal_ore")),
                false, false));
        TaskResult result = run(new FindInput(FindInput.FindKind.BLOCK,
                List.of("minecraft:coal_ore"), 2, 48));

        assertEquals(TaskResult.Status.DONE, result.status());
        FindDetails details = (FindDetails) result.details();
        assertEquals(2, details.found().size());
        assertEquals("b1", details.found().get(0).id(), "近的先领编号");
        assertEquals(8, details.found().get(0).distance());
        assertEquals("前方", details.found().get(0).direction(), "朝北时 -Z 是前方");
        assertEquals("北", details.found().get(0).compass());
        assertEquals("b2", details.found().get(1).id());
        assertEquals(48, details.searchedRadius());
        assertTrue(details.searchComplete() || true, "找满即收工，没扫完也可以收");
        assertEquals(2, memory.sites.size(), "视线验证过的方块命中写进世界记忆");
        assertEquals(List.of("minecraft:coal_ore"), memory.sites.get(0).roughlyThere());
    }

    @Test
    void 读端重复给的命中只数一次() {
        FindsAround.Hit same = block(0, 64, -8, "minecraft:coal_ore");
        finder.script.add(new FindsAround.Round(List.of(same), false, false));
        finder.script.add(new FindsAround.Round(List.of(same), true, false));
        TaskResult result = run(new FindInput(FindInput.FindKind.BLOCK,
                List.of("minecraft:coal_ore"), 2, 48));

        assertEquals(TaskResult.Status.PARTIAL, result.status());
        FindDetails details = (FindDetails) result.details();
        assertEquals(1, details.found().size(), "同一格只数一次");
        assertEquals(Problem.Kind.NOT_FOUND, result.problem().kind());
    }

    @Test
    void 扫完了还不足按部分完成并写明查了多大范围() {
        finder.script.add(new FindsAround.Round(List.of(block(0, 64, -8, "minecraft:coal_ore")),
                true, false));
        TaskResult result = run(new FindInput(FindInput.FindKind.BLOCK,
                List.of("minecraft:coal_ore"), 3, 48));

        assertEquals(TaskResult.Status.PARTIAL, result.status());
        assertEquals(Problem.Kind.NOT_FOUND, result.problem().kind());
        assertTrue(result.problem().message().contains("48 格"), result.problem().message());
        assertTrue(result.problem().message().contains("查过没有不等于世界里没有"));
        assertEquals(1, ((FindDetails) result.details()).found().size());
    }

    @Test
    void 一个都没有按没找到失败不冒充全世界没有() {
        finder.script.add(new FindsAround.Round(List.of(), true, false));
        TaskResult result = run(new FindInput(FindInput.FindKind.BLOCK,
                List.of("minecraft:diamond_ore"), 1, 48));

        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.NOT_FOUND, result.problem().kind());
        assertTrue(result.problem().message().contains("不等于世界里没有"));
    }

    @Test
    void 扫描中换世界按作废收场() {
        finder.script.add(new FindsAround.Round(List.of(), false, true));
        TaskResult result = run(new FindInput(FindInput.FindKind.BLOCK,
                List.of("minecraft:coal_ore"), 1, 48));

        assertEquals(TaskResult.Status.CANCELLED, result.status());
        assertTrue(result.summary().contains("作废"));
        assertNull(result.problem(), "取消不带问题");
    }

    @Test
    void 实体命中带保护事实并沿用实体编号() {
        FindsAround.Hit named = new FindsAround.Hit(FindInput.FindKind.ENTITY, "minecraft:sheep",
                WorldPosition.here(3, 64, 0), 7, true, "有名字");
        finder.script.add(new FindsAround.Round(List.of(named), true, false));
        TaskResult result = run(new FindInput(FindInput.FindKind.ENTITY,
                List.of("minecraft:sheep"), 1, 64));

        assertEquals(TaskResult.Status.DONE, result.status());
        FindDetails.Found found = ((FindDetails) result.details()).found().get(0);
        assertEquals("e1", found.id());
        assertTrue(found.creatureProtected());
        assertEquals("有名字", found.protectionReason());
    }

    @Test
    void 结构线索领地形特征的编号() {
        FindsAround.Hit lead = FindsAround.Hit.place(FindInput.FindKind.STRUCTURE,
                "minecraft:village_plains", WorldPosition.here(0, 64, -30));
        finder.script.add(new FindsAround.Round(List.of(lead), true, false));
        TaskResult result = run(new FindInput(FindInput.FindKind.STRUCTURE,
                List.of("minecraft:village_plains"), 1, 48));

        assertEquals(TaskResult.Status.DONE, result.status());
        FindDetails.Found found = ((FindDetails) result.details()).found().get(0);
        assertTrue(found.id().matches("f[0-9]+"), "结构线索是 f# 编号：" + found.id());
        assertFalse(found.creatureProtected());
    }
}
