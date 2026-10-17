// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * 浇筑建门：直岸整条顺序走完框成十格、模具收回；池只有一格深先挖再浇；起手已成直接砌模具；
 * 倒出圆石不重倒；没水按缺东西结束；中途舀不到岩浆停下仍拆模具、岩浆倒回；
 * 交施工引擎的放挡块跟着浇筑暂停、先停稳、收尾，能不能停看施工手上的。
 */
class PortalLavaCastingTest {

    private static final PortalCastingLayout LAYOUT = new PortalCastingLayout(new BlockPos(10, 64, 10), Direction.NORTH);

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 刻数往前走：等退水那一步按刻数判。 */
    private static final class Ticks implements TickContext {
        long tick;

        @Override public long gameTick() {
            return tick++;
        }

        @Override public PlayerContext player() {
            return null;
        }
    }

    private static Function<BlockPos, Optional<BlockState>> readOf(Map<BlockPos, BlockState> world) {
        return at -> Optional.ofNullable(world.get(at));
    }

    private record Scene(DimensionFakes.FakeWorld view, CastingFakes site, LavaPoolSurvey.Candidate candidate) {}

    private static Scene scene(int poolDepth) {
        Map<BlockPos, BlockState> world = CastingFakes.straightShore(LAYOUT, poolDepth);
        DimensionFakes.FakeWorld view = new DimensionFakes.FakeWorld();
        view.blocks.putAll(world);
        CastingFakes site = new CastingFakes(view.blocks);
        site.allowFlowAround(LAYOUT);
        site.feet = LAYOUT.cell(0, 1, 2);
        LavaPoolSurvey.Verdict verdict = LavaPoolSurvey.judge(LAYOUT, readOf(view.blocks), at -> true, site.feet);
        assertTrue(verdict instanceof LavaPoolSurvey.Verdict.Ok, verdict.toString());
        return new Scene(view, site, ((LavaPoolSurvey.Verdict.Ok) verdict).candidate());
    }

    private static DimensionTravelServices services(Scene scene) {
        return new DimensionTravelServices(scene.view(), () -> new DimensionFakes.FakeContext(), new DimensionFakes.FakeWalks(),
                scene.site(), new DimensionFakes.FakeToMainhand(), new DimensionFakes.FakeUses(),
                permissions -> scene.site().services(), null, () -> List.of(), progress -> {});
    }

    private static PortalLavaCasting casting(Scene scene) {
        // 世界视图替身的背包跟现场替身同步一次：石料够不够按它看。
        scene.view().stacks.clear();
        scene.view().stacks.addAll(scene.site().stacks());
        return new PortalLavaCasting(scene.candidate(), Permissions.DEFAULT, services(scene), scene.site().services());
    }

    private static TaskResult run(Scene scene) {
        PortalLavaCasting casting = casting(scene);
        Ticks ticks = new Ticks();
        casting.start(ticks);
        for (int i = 0; i < 20000; i++) {
            if (casting.tick(ticks) instanceof TickResult.Finished finished) {
                return finished.result();
            }
        }
        throw new AssertionError("两万刻都没做完");
    }

    @Test
    void straightShoreIsCastIntoAFullFrameAndTheMoldIsRemoved() {
        Scene scene = scene(2);
        scene.site().give(CastingFakes.BUCKET, 1);
        scene.site().give(CastingFakes.COBBLESTONE, 16);
        TaskResult result = run(scene);

        assertEquals(TaskResult.Status.DONE, result.status(), result.summary() + " / " + result.problem());
        assertTrue(LAYOUT.frame().ready(readOf(scene.view().blocks)), "十格框都是黑曜石、门内空着");
        for (BlockPos at : LAYOUT.mold()) {
            assertFalse(scene.view().blocks.get(at).is(Blocks.COBBLESTONE), "模具拆了：" + at.toShortString());
        }
        assertTrue(scene.site().temporaries.isEmpty(), "模具的临时方块账销了");
        // 水是拿空桶去舀的；每桶岩浆都从模板范围之外的池面舀。
        assertTrue(scene.site().asked.stream().anyMatch(r -> CastingFakes.WATER_BUCKET.equals(r.wanted().itemId())));
        assertTrue(scene.site().scooped.stream().filter(at -> !LAYOUT.footprint().contains(at)).count() >= 6, "舀了几桶岩浆");
        assertEquals(0, scene.site().carried(CastingFakes.LAVA_BUCKET), "收尾桶里没有岩浆");
        assertTrue(result.summary().contains("浇出"), result.summary());
    }

    @Test
    void shallowPoolDigsTheStoneUnderTheBottomRowBeforeCasting() {
        Scene scene = scene(1);
        scene.site().give(CastingFakes.BUCKET, 1);
        scene.site().give(CastingFakes.COBBLESTONE, 16);
        TaskResult result = run(scene);

        assertEquals(TaskResult.Status.DONE, result.status(), result.summary() + " / " + result.problem());
        for (BlockPos at : LAYOUT.bottom()) {
            assertTrue(scene.site().dug.contains(at), "底行下面的石头先挖：" + at.toShortString());
            assertTrue(scene.view().blocks.get(at).is(Blocks.OBSIDIAN), "挖完浇成黑曜石：" + at.toShortString());
        }
    }

    @Test
    void halfCastStartSkipsStraightToTheMold() {
        Scene scene = scene(2);
        for (BlockPos at : LAYOUT.startFrameCells()) scene.view().blocks.put(at, Blocks.OBSIDIAN.defaultBlockState());
        for (BlockPos at : LAYOUT.startInterior()) scene.view().blocks.put(at, Blocks.AIR.defaultBlockState());
        scene.site().give(CastingFakes.BUCKET, 1);
        scene.site().give(CastingFakes.COBBLESTONE, 16);
        TaskResult result = run(scene);

        assertEquals(TaskResult.Status.DONE, result.status(), result.summary() + " / " + result.problem());
        assertFalse(scene.site().placed.contains(LAYOUT.placeholder()), "起手已成就不再放挡块");
        assertFalse(scene.site().poured.contains(LAYOUT.initialWater()), "也不再倒起手水");
        assertTrue(LAYOUT.frame().ready(readOf(scene.view().blocks)));
    }

    @Test
    void pourThatMakesCobblestoneIsRecordedAndNotPouredTwice() {
        Scene scene = scene(2);
        BlockPos spoiled = LAYOUT.cell(-1, 2, 0);
        scene.site().castsCobblestone.add(spoiled);
        scene.site().give(CastingFakes.BUCKET, 1);
        scene.site().give(CastingFakes.COBBLESTONE, 16);
        TaskResult result = run(scene);

        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(1, scene.site().poured.stream().filter(spoiled::equals).count(), "那格只倒一桶");
        assertTrue(scene.view().blocks.get(spoiled).is(Blocks.COBBLESTONE), "产物留在现场不拆");
        assertTrue(result.changes().stream().anyMatch(c -> c.note() != null && c.note().contains("没成黑曜石")),
                "如实记成了别的：" + result.changes());
        assertFalse(LAYOUT.frame().ready(readOf(scene.view().blocks)), "框缺一格，由验框说");
    }

    @Test
    void noWaterNearbyEndsWithNeedItem() {
        Scene scene = scene(2);
        scene.site().waterNearby = false;
        scene.site().give(CastingFakes.BUCKET, 1);
        scene.site().give(CastingFakes.COBBLESTONE, 16);
        TaskResult result = run(scene);

        assertEquals(TaskResult.Status.FAILED, result.status(), result.summary());
        assertEquals(Problem.Kind.NEED_ITEM, result.problem().kind());
        assertTrue(result.problem().message().contains("水"), result.problem().message());
        assertTrue(scene.site().poured.isEmpty(), "没水就不开浇");
    }

    @Test
    void stoppingMidwayStillRemovesTheMoldAndReturnsTheLava() {
        Scene scene = scene(2);
        scene.site().give(CastingFakes.BUCKET, 1);
        scene.site().give(CastingFakes.COBBLESTONE, 16);
        // 池子小得只够起手：模具砌好后池面就没岩浆源可舀了。
        for (int across = -8; across <= 9; across++) {
            for (int behind = -8; behind <= 0; behind++) {
                for (int up = -1; up <= 0; up++) {
                    BlockPos at = LAYOUT.cell(across, up, behind);
                    boolean keep = behind >= -2 && across >= -3 && across <= 4;
                    if (!keep) scene.view().blocks.put(at, Blocks.STONE.defaultBlockState());
                }
            }
        }
        LavaPoolSurvey.Verdict verdict = LavaPoolSurvey.judge(LAYOUT, readOf(scene.view().blocks), at -> true, scene.site().feet);
        assertTrue(verdict instanceof LavaPoolSurvey.Verdict.Ok, "勘察时余量够：" + verdict);
        Scene small = new Scene(scene.view(), scene.site(), ((LavaPoolSurvey.Verdict.Ok) verdict).candidate());
        TaskResult result = run(small);

        // 浇到一半舀不到岩浆：停下、拆模具、桶里没留岩浆、如实说差在哪。
        assertTrue(result.status() == TaskResult.Status.PARTIAL || result.status() == TaskResult.Status.DONE, result.summary());
        for (BlockPos at : LAYOUT.mold()) {
            assertFalse(scene.view().blocks.get(at).is(Blocks.COBBLESTONE), "模具照样拆：" + at.toShortString());
        }
        assertEquals(0, scene.site().carried(CastingFakes.LAVA_BUCKET), "岩浆倒回池子或从没舀上");
    }

    @Test
    void placingTheStopperIsPausedAndClosedWithTheCasting() {
        // 放挡块交施工引擎按动作推进：施工正走向那一格时，浇筑的暂停、先到安全处再停、收尾转到施工手上，能不能停也看它的。
        Scene scene = scene(2);
        scene.site().give(CastingFakes.BUCKET, 1);
        scene.site().give(CastingFakes.COBBLESTONE, 16);
        HeldAction walking = new HeldAction();
        scene.site().approach = walking;
        PortalLavaCasting casting = casting(scene);
        Ticks ticks = new Ticks();
        casting.start(ticks);
        for (int i = 0; i < 400 && walking.ticks == 0; i++) {
            assertFalse(casting.tick(ticks) instanceof TickResult.Finished, "浇筑没走到放挡块就收场了");
        }
        assertTrue(walking.ticks > 0, "施工走在半路");
        assertTrue(casting.describe().contains("放挡块"), casting.describe());

        assertEquals(Interruptibility.UNSAFE_TO_STOP, casting.interruptibility(ticks),
                "施工手上那一下停不得，浇筑就停不得");
        assertTrue(casting.stopSafely(), "叫浇筑先到安全处再停，转给施工手上那一下去落地");
        assertEquals(1, walking.stops, "走着的那一下收到先停稳");
        assertFalse(casting.stoppedSafely(), "那一下还没停稳，浇筑也没停稳");
        casting.pause();
        assertEquals(1, walking.pauses, "浇筑暂停，施工跟着停下松手");
        casting.close(CloseReason.CANCELLED);
        assertEquals(1, walking.closes, "浇筑收尾，施工跟着收尾");
    }

    /** 一直在走、停不得的一下：记下被推进、叫停稳、暂停、收尾了几次。 */
    private static final class HeldAction implements Action {
        int ticks;
        int stops;
        int pauses;
        int closes;

        @Override public ActionStatus tick(TickContext context) {
            ticks++;
            return ActionStatus.running();
        }

        // 悬在半空：先到安全处再停要先落地，这几刻里一直没停稳。
        @Override public boolean stopSafely() {
            stops++;
            return true;
        }

        @Override public boolean stoppedSafely() {
            return false;
        }

        @Override public void pause() {
            pauses++;
        }

        @Override public void close() {
            closes++;
        }

        @Override public Interruptibility interruptibility() {
            return Interruptibility.UNSAFE_TO_STOP;
        }

        @Override public String describe() {
            return "停不得的一下";
        }
    }
}
