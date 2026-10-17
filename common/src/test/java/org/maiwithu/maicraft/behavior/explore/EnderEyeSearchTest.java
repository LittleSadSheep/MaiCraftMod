// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.perception.RemembersSightings;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/** 扔末影之眼找要塞的一趟：钻地收工、捡回落下的眼、沿飞向走一段再交叉、碎了记消耗、没眼与眼不飞如实说。 */
class EnderEyeSearchTest {

    /** 扔眼的现场替身：按脚本给每一扔的经过，走一段就把脚挪到那一柱列，捡回就多一只眼。 */
    private static final class FakeEyes implements FollowsEnderEyes {
        int carried;
        int x;
        int z;
        final Deque<Optional<EyeFlight>> flights = new ArrayDeque<>();
        final List<int[]> walkedTo = new ArrayList<>();
        /** 下一段走路走不通、走到一半停在哪：为 null 时走到目的地。 */
        int[] stuckAt;
        int fetches;

        @Override public int eyesCarried() { return carried; }

        @Override public Optional<WorldPosition> feet() {
            return Optional.of(new WorldPosition(x, 64, z, "minecraft:overworld"));
        }

        @Override public EyeThrow throwEye() {
            Optional<EyeFlight> flight = flights.remove();
            return new EyeThrow() {
                @Override public Optional<EyeFlight> flight() { return flight; }
                @Override public ActionStatus tick(TickContext context) {
                    // 原版没有要塞时眼不飞也不扣；飞起来就少一只。
                    if (flight.isPresent()) carried--;
                    return ActionStatus.done();
                }
                @Override public String describe() { return "演出来的扔眼"; }
            };
        }

        @Override public Action fetch(int dropEntity) {
            fetches++;
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    carried++;
                    return ActionStatus.done();
                }
                @Override public String describe() { return "演出来的捡回"; }
            };
        }

        @Override public WalkRun walkToward(int toX, int toZ, TerrainPermit permit) {
            walkedTo.add(new int[] {toX, toZ});
            int[] stop = stuckAt;
            stuckAt = null;
            return new WalkRun() {
                private WalkReport report = WalkReport.planning();
                @Override public ActionStatus tick(TickContext context) {
                    if (stop != null) {
                        x = stop[0];
                        z = stop[1];
                        Problem problem = Problem.of(Problem.Kind.UNREACHABLE, "前面是大海");
                        report = WalkReport.failed(problem, new BlockPos(x, 64, z), false);
                        return ActionStatus.failed(problem);
                    }
                    x = toX;
                    z = toZ;
                    report = WalkReport.arrived(new BlockPos(x, 64, z), false);
                    return ActionStatus.done();
                }
                @Override public WalkReport report() { return report; }
                @Override public boolean stop() { return true; }
                @Override public String describe() { return "演出来的走一段"; }
            };
        }

        // 站在脚下扔，眼朝 (towardX, towardZ) 横飞 12 格后落下或碎掉。
        void flies(double towardX, double towardZ, EyeFlight.Landing landing) {
            double length = Math.hypot(towardX - x, towardZ - z);
            flights.add(Optional.of(new EyeFlight(x, 65, z, x + (towardX - x) / length * 12, 73,
                    z + (towardZ - z) / length * 12, landing, landing == EyeFlight.Landing.DROPPED ? 9 : -1)));
        }
    }

    /** 记账口替身：收下变化与试过的办法。 */
    private static final class Records implements TaskRecords {
        final List<Change> changes = new ArrayList<>();
        final List<String> attempts = new ArrayList<>();

        @Override public void change(Change change) { changes.add(change); }
        @Override public void unconfirmed(Change change) { }
        @Override public void attempt(String tried, String whatHappened) { attempts.add(tried + "：" + whatHappened); }
    }

    /** 世界记忆替身：收下记的产地线索。 */
    private static final class Memory implements RemembersSightings {
        final List<WorldPosition> sites = new ArrayList<>();
        final List<List<String>> contents = new ArrayList<>();

        @Override public void containerSeen(WorldPosition position, String blockType, Instant when) { }
        @Override public void workstationSeen(WorldPosition position, String blockType, Instant when) { }
        @Override public void siteSeen(WorldPosition position, List<String> roughlyThere, Instant when) {
            sites.add(position);
            contents.add(roughlyThere);
        }
    }

    private final FakeEyes eyes = new FakeEyes();
    private final Records records = new Records();
    private final Memory memory = new Memory();
    private final EnderEyeSearch search = new EnderEyeSearch(eyes, memory, TerrainPermit.NATURAL, records);

    private ActionStatus run() {
        ExploreFakes.TestTick tick = new ExploreFakes.TestTick();
        for (int i = 0; i < 200; i++) {
            tick.nextTick();
            ActionStatus status = search.tick(tick);
            if (!(status instanceof ActionStatus.Running)) return status;
        }
        throw new AssertionError("扔眼两百刻都没结束：" + search.describe());
    }

    @Test
    void 眼往下钻就把要塞记成线索并捡回眼() {
        eyes.carried = 3;
        eyes.flights.add(Optional.of(new EyeFlight(0, 65, 0, 5, 40, -4, EyeFlight.Landing.DROPPED, 9)));

        assertInstanceOf(ActionStatus.Done.class, run());
        assertEquals(new WorldPosition(5, 64, -4, "minecraft:overworld"), search.conclusion().orElseThrow());
        assertEquals(List.of(EnderEyeSearch.STRONGHOLD), memory.contents.get(0), "按末地门房间认得的说法记线索");
        assertEquals(1, eyes.fetches, "落下的眼走过去捡回");
        assertEquals(3, eyes.carried);
        assertTrue(search.summary().contains("(5, -4)") && search.summary().contains("扔了 1 只，碎了 0 只，捡回 1 只"),
                search.summary());
    }

    @Test
    void 沿飞向走一段再扔两次飞向交出位置() {
        eyes.carried = 4;
        // 要塞在 (60, -100)：先在原点扔、往北走第一段，再扔一只就交出来了。
        eyes.flies(60, -100, EyeFlight.Landing.DROPPED);
        ActionStatus first = null;
        ExploreFakes.TestTick tick = new ExploreFakes.TestTick();
        for (int i = 0; i < 20 && eyes.walkedTo.isEmpty(); i++) {
            tick.nextTick();
            first = search.tick(tick);
        }
        assertInstanceOf(ActionStatus.Running.class, first);
        assertEquals(1, eyes.walkedTo.size(), "第一扔之后往眼指的方向走一段");
        // 走完第一段：路上绕了一下，停在 (0, -80) 再扔，第二只眼往东北偏得很明显。
        tick.nextTick();
        search.tick(tick);
        eyes.x = 0;
        eyes.z = -80;
        eyes.flies(60, -100, EyeFlight.Landing.SHATTERED);

        assertInstanceOf(ActionStatus.Done.class, run());
        WorldPosition at = search.conclusion().orElseThrow();
        assertEquals(60, at.x(), 1);
        assertEquals(-100, at.z(), 1);
        assertTrue(records.changes.contains(Change.of(Change.Kind.ITEM_CONSUMED, EnderEyeSearch.ENDER_EYE, 1)),
                "碎了的那只记成消耗：" + records.changes);
        assertTrue(search.summary().contains("碎了 1 只"), search.summary());
        assertEquals(2, records.attempts.size(), "每一扔记一条经过：" + records.attempts);
    }

    @Test
    void 身上没眼按缺东西说() {
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, run());

        assertEquals(Problem.Kind.NEED_ITEM, failed.problem().kind());
        assertTrue(failed.problem().suggestion().contains("obtain"), failed.problem().suggestion());
    }

    @Test
    void 眼不飞就是这个世界没有要塞() {
        eyes.carried = 2;
        eyes.flights.add(Optional.empty());

        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, run());
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, failed.problem().kind());
        assertEquals(2, eyes.carried, "没飞起来不扣眼，也不再扔第二只");
    }

    @Test
    void 眼用完了带上最后指的方向() {
        eyes.carried = 1;
        eyes.flies(0, -2000, EyeFlight.Landing.SHATTERED);

        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, run());
        assertEquals(Problem.Kind.NEED_ITEM, failed.problem().kind());
        assertTrue(failed.problem().message().contains("往北飞"), failed.problem().message());
        assertEquals(1, eyes.walkedTo.size(), "碎了也照方向走了一段");
    }

    @Test
    void 一步都没走出去就按到不了收场() {
        eyes.carried = 2;
        eyes.flies(0, -2000, EyeFlight.Landing.SHATTERED);
        eyes.stuckAt = new int[] {0, -3};

        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, run());
        assertEquals(Problem.Kind.UNREACHABLE, failed.problem().kind());
        assertTrue(failed.problem().message().contains("前面是大海"), failed.problem().message());
    }

    @Test
    void 走出一截走不通就在那里再扔() {
        eyes.carried = 2;
        eyes.flies(0, -2000, EyeFlight.Landing.SHATTERED);
        eyes.stuckAt = new int[] {0, -30};
        // 在走不通的地方再扔一只：这回眼往下钻，要塞就在那里。
        eyes.flights.add(Optional.of(new EyeFlight(0, 65, -30, 2, 40, -36, EyeFlight.Landing.SHATTERED, -1)));

        assertInstanceOf(ActionStatus.Done.class, run());
        assertEquals(-36, search.conclusion().orElseThrow().z());
        assertTrue(records.attempts.stream().anyMatch(line -> line.contains("就在这里再扔")), records.attempts.toString());
    }
}
