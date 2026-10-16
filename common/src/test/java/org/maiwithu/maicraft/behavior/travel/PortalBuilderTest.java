// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import org.maiwithu.maicraft.behavior.construction.Blueprint;
import org.maiwithu.maicraft.game.player.PlayerContext;
import java.util.function.Function;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.DigsBlocks;
import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.construction.ConstructionSeams;
import org.maiwithu.maicraft.behavior.construction.ConstructionServices;
import org.maiwithu.maicraft.behavior.construction.PlacementConfirmation;
import org.maiwithu.maicraft.behavior.construction.PlacementPrediction;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.behavior.interaction.InteractionResult;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 建门：残缺的黑曜石框补齐而不是新建、点火用品备不齐 NEED_ITEM、
 * 施工做完门框没成型就结束（保留施工事实、不循环）、点着了验门面。
 */
class PortalBuilderTest {

    private static final Direction.Axis AXIS = Direction.Axis.X;
    private static final NetherPortalFrame FRAME =
            new NetherPortalFrame(new BlockPos(10, 65, 10), AXIS, 2, 3);

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static TickContext tick() {
        return new TickContext() {
            @Override public long gameTick() {
                return 0;
            }

            @Override public PlayerContext player() {
                return null;
            }
        };
    }

    /** 把FRAME的整圈黑曜石摆进世界，可指定抽掉哪几格（残缺的框）。 */
    private static void layFrame(Map<BlockPos, BlockState> world, BlockPos... broken) {
        for (BlockPos cell : FRAME.frame()) {
            world.put(cell, Blocks.OBSIDIAN.defaultBlockState());
        }
        for (BlockPos cell : FRAME.interior()) {
            world.put(cell, Blocks.AIR.defaultBlockState());
        }
        for (BlockPos cell : broken) {
            world.remove(cell);
        }
    }

    /** 驱动建门任务到结束；跑不完按失败收场。 */
    private static TaskResult run(PortalBuilder builder) {
        builder.start(tick());
        for (long i = 0; i < 20000; i++) {
            if (builder.tick(tick()) instanceof TickResult.Finished finished) {
                return finished.result();
            }
        }
        throw new AssertionError("两万刻都没做完");
    }

    private DimensionTravelServices services(DimensionFakes.FakeWorld world, DimensionFakes.FakeObtain obtain,
            ConstructionFakes site, DimensionFakes.FakeUses uses) {
        return new DimensionTravelServices(world, () -> new DimensionFakes.FakeContext(), new DimensionFakes.FakeWalks(),
                obtain, new DimensionFakes.FakeToMainhand(), uses,
                permissions -> site.services(), null, () -> List.of(), progress -> {});
    }

    /** 点火生效的替身：对点火格使用后，门框内侧长满门方块（原版一次填满整扇）。 */
    private static DimensionFakes.FakeUses lightsThePortal(DimensionFakes.FakeWorld view) {
        DimensionFakes.FakeUses uses = new DimensionFakes.FakeUses();
        uses.effects.add(() -> {
            for (BlockPos cell : FRAME.interior()) {
                view.blocks.put(cell, Blocks.NETHER_PORTAL.defaultBlockState());
            }
        });
        return uses;
    }

    @Test
    void brokenFrameIsRepairedInsteadOfBuildingANewOne() {
        Map<BlockPos, BlockState> world = new HashMap<>();
        BlockPos brokenTop = FRAME.cell(1, 3);
        layFrame(world, brokenTop);
        DimensionFakes.FakeWorld view = new DimensionFakes.FakeWorld();
        view.blocks.putAll(world);
        view.enqueueScan(new DimensionWorldView.Scan(List.of(FRAME.cell(0, -1)), true));
        view.carried = PortalBuilder.FLINT_AND_STEEL;
        ConstructionFakes site = new ConstructionFakes(view.blocks);
        TaskResult result = run(new PortalBuilder(Permissions.DEFAULT, 128, services(view,
                new DimensionFakes.FakeObtain(), site, lightsThePortal(view))));

        assertEquals(TaskResult.Status.DONE, result.status(), result.summary() + " / " + result.problem());
        // 施工只补缺的那一格，整圈黑曜石没有推倒重来。
        assertTrue(view.blocks.containsKey(brokenTop), "缺的格补上了");
        assertEquals(1, site.placed.size(), "只放了一格：" + site.placed);
        // 门框齐了直接点火：门面出现，任务以建成收场。
        assertTrue(result.summary().contains("下界门点着了"), result.summary());
    }

    @Test
    void noIgnitionItemEndsWithNeedItem() {
        Map<BlockPos, BlockState> world = new HashMap<>();
        layFrame(world, FRAME.cell(1, 3));
        DimensionFakes.FakeWorld view = new DimensionFakes.FakeWorld();
        view.blocks.putAll(world);
        view.enqueueScan(new DimensionWorldView.Scan(List.of(FRAME.cell(0, -1)), true));
        // 打火石与火弹都弄不到：如实 NEED_ITEM，不开工建框。
        DimensionFakes.FakeObtain obtain = new DimensionFakes.FakeObtain();
        obtain.answers.add(Problem.of(Problem.Kind.NOT_FOUND, "附近没有砂砾", null));
        obtain.answers.add(Problem.of(Problem.Kind.NOT_FOUND, "附近没有火药", null));
        TaskResult result = run(new PortalBuilder(Permissions.DEFAULT, 128, services(view, obtain,
                new ConstructionFakes(view.blocks), new DimensionFakes.FakeUses())));

        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.NEED_ITEM, result.problem().kind());
        assertTrue(result.problem().message().contains("打火石"), result.problem().message());
        assertTrue(view.blocks.get(FRAME.cell(1, 3)) == null, "备不齐就不开工，门框保持残缺");
    }

    @Test
    void frameStillIncompleteAfterConstructionEndsWithoutLooping() {
        Map<BlockPos, BlockState> world = new HashMap<>();
        BlockPos brokenTop = FRAME.cell(1, 3);
        layFrame(world, brokenTop);
        DimensionFakes.FakeWorld view = new DimensionFakes.FakeWorld();
        view.blocks.putAll(world);
        view.enqueueScan(new DimensionWorldView.Scan(List.of(FRAME.cell(0, -1)), true));
        view.carried = PortalBuilder.FLINT_AND_STEEL;
        ConstructionFakes site = new ConstructionFakes(view.blocks);
        site.failPlacing = true;
        TaskResult result = run(new PortalBuilder(Permissions.DEFAULT, 128, services(view,
                new DimensionFakes.FakeObtain(), site, new DimensionFakes.FakeUses())));

        // 施工交付了但那格还是没黑曜石：验框接住"还缺 1 格"的没成型，结束不循环。
        assertEquals(TaskResult.Status.PARTIAL, result.status(), result.summary());
        assertTrue(result.problem().message().contains("还缺 1 格黑曜石"), result.problem().message());
        assertTrue(view.blocks.get(brokenTop) == null || !view.blocks.get(brokenTop).is(Blocks.OBSIDIAN),
                "没放成的那格保持没放成，不冒充");
    }

    @Test
    void frameNotReadyAfterConstructionEndsWithoutLooping() {
        Map<BlockPos, BlockState> world = new HashMap<>();
        BlockPos brokenTop = FRAME.cell(1, 3);
        layFrame(world, brokenTop);
        DimensionFakes.FakeWorld view = new DimensionFakes.FakeWorld();
        view.blocks.putAll(world);
        view.enqueueScan(new DimensionWorldView.Scan(List.of(FRAME.cell(0, -1)), true));
        view.carried = PortalBuilder.FLINT_AND_STEEL;
        // 施工把缺格补上，但这一格里同时掉进了别的东西：门框齐整也验不出可点火的状态。
        ConstructionFakes site = new ConstructionFakes(view.blocks);
        BlockPos blocked = FRAME.cell(1, 1);
        site.afterPlace = () -> view.blocks.put(blocked, Blocks.STONE.defaultBlockState());
        TaskResult result = run(new PortalBuilder(Permissions.DEFAULT, 128, services(view,
                new DimensionFakes.FakeObtain(), site, new DimensionFakes.FakeUses())));

        assertEquals(TaskResult.Status.PARTIAL, result.status(), result.summary());
        assertTrue(result.problem().message().contains("门框没成型"), result.problem().message());
        assertTrue(view.blocks.get(blocked).is(Blocks.STONE), "占着内侧的那格没被悄悄清掉");
    }

    @Test
    void litPortalIsVerifiedByThePortalBlock() {
        Map<BlockPos, BlockState> world = new HashMap<>();
        layFrame(world);
        DimensionFakes.FakeWorld view = new DimensionFakes.FakeWorld();
        view.blocks.putAll(world);
        view.enqueueScan(new DimensionWorldView.Scan(List.of(FRAME.cell(0, -1)), true));
        view.carried = PortalBuilder.FLINT_AND_STEEL;
        // 点火生效：门框内侧长满门方块（原版一次填满整扇）。
        DimensionFakes.FakeUses uses = new DimensionFakes.FakeUses();
        uses.effects.add(() -> {
            for (BlockPos cell : FRAME.interior()) {
                view.blocks.put(cell, Blocks.NETHER_PORTAL.defaultBlockState());
            }
        });
        TaskResult result = run(new PortalBuilder(Permissions.DEFAULT, 128, new DimensionTravelServices(
                view, () -> new DimensionFakes.FakeContext(), new DimensionFakes.FakeWalks(),
                new DimensionFakes.FakeObtain(), new DimensionFakes.FakeToMainhand(), uses,
                permissions -> new ConstructionFakes(view.blocks).services(), null,
                () -> List.of(), progress -> {})));

        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertTrue(result.summary().contains("门面已出现"), result.summary());
    }

    @Test
    void chooseSiteFindsFlatGroundNextToThePlayer() {
        Map<BlockPos, BlockState> world = new HashMap<>();
        // 一片平地：脚边石头地面，上面几层是加载着的空气。
        for (int x = -10; x <= 10; x++) {
            for (int z = -10; z <= 10; z++) {
                world.put(new BlockPos(x, 63, z), Blocks.STONE.defaultBlockState());
                for (int y = 64; y <= 70; y++) {
                    world.put(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
        var site = PortalBuilder.chooseSite(readOf(world), new BlockPos(0, 64, 0), 8);
        assertTrue(site.isPresent(), "平地上挑得到门址");
        NetherPortalFrame frame = site.get();
        for (BlockPos cell : frame.frame()) {
            // 底行黑曜石下面就是实地；顶行与立柱悬在空气里是正常的。
            if (cell.getY() == frame.origin().getY() - 1) {
                assertTrue(world.get(cell.below()).is(Blocks.STONE), "门框立在实地上：" + cell);
            }
        }
        // 脚边一格不大的平台放不下整圈门框时挑不到，如实给空。
        Map<BlockPos, BlockState> tiny = new HashMap<>();
        tiny.put(new BlockPos(0, 63, 0), Blocks.STONE.defaultBlockState());
        assertTrue(PortalBuilder.chooseSite(readOf(tiny), new BlockPos(0, 64, 0), 2).isEmpty());
    }

    /** 给离线测试把蓝图交出去（核对建门只编缺的格）。 */
    @Test
    void frameBlueprintOnlyNamesTheMissingCells() {
        Map<BlockPos, BlockState> world = new HashMap<>();
        layFrame(world, FRAME.cell(1, 3));
        var blueprint = PortalBuilder.frameBlueprint("minecraft:overworld", FRAME, readOf(world));
        assertEquals(1, blueprint.cells().size(), "补齐只放缺的格");
        assertEquals(FRAME.cell(1, 3), blueprint.cells().get(0).pos());

        Map<BlockPos, BlockState> full = new HashMap<>();
        layFrame(full);
        var whole = PortalBuilder.frameBlueprint("minecraft:overworld", FRAME, readOf(full));
        assertEquals(FRAME.frame().size(), whole.cells().size(), "框齐整时蓝图是整圈");
    }

    private static final PortalCastingLayout SHORE = new PortalCastingLayout(new BlockPos(10, 64, 10), Direction.NORTH);

    /** 一片平地：脚边石头地面，上面几层是加载着的空气。 */
    private static Map<BlockPos, BlockState> flatGround() {
        Map<BlockPos, BlockState> world = new HashMap<>();
        for (int x = -10; x <= 10; x++) {
            for (int z = -10; z <= 10; z++) {
                world.put(new BlockPos(x, 63, z), Blocks.STONE.defaultBlockState());
                for (int y = 64; y <= 70; y++) {
                    world.put(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
        return world;
    }

    private static BackpackStack stack(String itemId, int count) {
        return new BackpackStack(itemId, count, 64, false, false, false, true);
    }

    private static DimensionFakes.FakeUses lights(DimensionFakes.FakeWorld view, NetherPortalFrame frame) {
        DimensionFakes.FakeUses uses = new DimensionFakes.FakeUses();
        uses.effects.add(() -> {
            for (BlockPos cell : frame.interior()) {
                view.blocks.put(cell, Blocks.NETHER_PORTAL.defaultBlockState());
            }
        });
        return uses;
    }

    @Test
    void castsAtTheLavaPoolWhenCarryingNoObsidian() {
        DimensionFakes.FakeWorld view = new DimensionFakes.FakeWorld();
        view.blocks.putAll(CastingFakes.straightShore(SHORE, 2));
        view.feet = SHORE.cell(0, 1, 2);
        view.carried = PortalBuilder.FLINT_AND_STEEL;
        CastingFakes site = new CastingFakes(view.blocks);
        site.allowFlowAround(SHORE);
        site.feet = view.feet;
        site.give(CastingFakes.BUCKET, 1);
        site.give(CastingFakes.COBBLESTONE, 16);
        view.stacks.addAll(site.stacks());
        // 先扫黑曜石：附近没有框；再扫岩浆：池面几格源。
        view.enqueueScan(new DimensionWorldView.Scan(List.of(), true));
        view.enqueueScan(new DimensionWorldView.Scan(SHORE.startRow(), true));
        TaskResult result = run(new PortalBuilder(Permissions.DEFAULT, 128, new DimensionTravelServices(
                view, () -> new DimensionFakes.FakeContext(), new DimensionFakes.FakeWalks(), site,
                new DimensionFakes.FakeToMainhand(), lights(view, SHORE.frame()),
                permissions -> site.services(), null, () -> List.of(), progress -> {})));

        assertEquals(TaskResult.Status.DONE, result.status(), result.summary() + " / " + result.problem());
        assertTrue(result.details() instanceof PortalBuildDetails built
                && PortalBuildDetails.LAVA_CASTING.equals(built.portalBuiltHow()), "门是浇的：" + result.details());
        // 身上没黑曜石：没去弄黑曜石，门框是一桶一格浇出来的。
        assertTrue(site.asked.stream().noneMatch(r -> PortalBuilder.OBSIDIAN.equals(r.wanted().itemId())));
        assertTrue(site.poured.size() >= 6, "浇了几桶：" + site.poured.size());
        assertTrue(SHORE.frame().active(readOf(view.blocks)), "门点着了");
    }

    @Test
    void laysObsidianWhenCarryingEnoughEvenNextToLava() {
        DimensionFakes.FakeWorld view = new DimensionFakes.FakeWorld();
        view.blocks.putAll(flatGround());
        view.carried = PortalBuilder.FLINT_AND_STEEL;
        view.stacks.add(stack(PortalBuilder.OBSIDIAN, 10));
        view.enqueueScan(new DimensionWorldView.Scan(List.of(), true));
        NetherPortalFrame expected = PortalBuilder.chooseSite(readOf(view.blocks), view.feet, 8).orElseThrow();
        ConstructionFakes site = new ConstructionFakes(view.blocks);
        TaskResult result = run(new PortalBuilder(Permissions.DEFAULT, 128, services(view,
                new DimensionFakes.FakeObtain(), site, lights(view, expected))));

        assertEquals(TaskResult.Status.DONE, result.status(), result.summary() + " / " + result.problem());
        assertTrue(result.details() instanceof PortalBuildDetails built
                && PortalBuildDetails.OBSIDIAN.equals(built.portalBuiltHow()), "门是摆的：" + result.details());
        // 十格框摆齐、四角不碰；顶行没有可点的面时垫的那块收走了。
        assertTrue(expected.frame().stream().allMatch(cell -> view.blocks.get(cell).is(Blocks.OBSIDIAN)), "十格都是黑曜石");
        assertTrue(site.temporaries.isEmpty(), "垫脚的临时方块收回了");
        assertEquals(PortalCastingLayout.FRAME_CELLS,
                site.placed.stream().filter(cell -> view.blocks.get(cell).is(Blocks.OBSIDIAN)).count(), "摆了十块黑曜石");
    }

    @Test
    void withoutPoolOrObsidianFallsBackToLayingAndReportsTheSurvey() {
        DimensionFakes.FakeWorld view = new DimensionFakes.FakeWorld();
        view.blocks.putAll(flatGround());
        view.carried = PortalBuilder.FLINT_AND_STEEL;
        // 黑曜石与岩浆都扫完没有。
        view.enqueueScan(new DimensionWorldView.Scan(List.of(), true));
        view.enqueueScan(new DimensionWorldView.Scan(List.of(), true));
        NetherPortalFrame expected = PortalBuilder.chooseSite(readOf(view.blocks), view.feet, 8).orElseThrow();
        ConstructionFakes site = new ConstructionFakes(view.blocks);
        TaskResult result = run(new PortalBuilder(Permissions.DEFAULT, 128, services(view,
                new DimensionFakes.FakeObtain(), site, lights(view, expected))));

        assertEquals(TaskResult.Status.DONE, result.status(), result.summary() + " / " + result.problem());
        assertTrue(result.details() instanceof PortalBuildDetails built
                && PortalBuildDetails.OBSIDIAN.equals(built.portalBuiltHow()));
        // 勘察事实进试过的办法：LLM 知道附近为什么浇不了。
        assertTrue(result.attempts().stream().anyMatch(a -> a.tried().contains("岩浆池") && a.result().contains("岩浆源")),
                "勘察事实：" + result.attempts());
    }

    @Test
    void layingTheFrameIsPausedAndClosedWithTheBuilder() {
        // 砌框交施工引擎按动作推进：施工正走向下一格时，建门的暂停、收尾转到施工手上，能不能停也看它的。
        Map<BlockPos, BlockState> world = new HashMap<>();
        layFrame(world, FRAME.cell(1, 3));
        DimensionFakes.FakeWorld view = new DimensionFakes.FakeWorld();
        view.blocks.putAll(world);
        view.enqueueScan(new DimensionWorldView.Scan(List.of(FRAME.cell(0, -1)), true));
        view.carried = PortalBuilder.FLINT_AND_STEEL;
        ConstructionFakes site = new ConstructionFakes(view.blocks);
        HeldAction walking = new HeldAction();
        site.approach = walking;
        PortalBuilder builder = new PortalBuilder(Permissions.DEFAULT, 128, services(view,
                new DimensionFakes.FakeObtain(), site, new DimensionFakes.FakeUses()));
        runUntilBusy(builder, walking);

        assertEquals(Interruptibility.UNSAFE_TO_STOP, builder.interruptibility(tick()),
                "施工手上那一下停不得，建门就停不得");
        builder.pause();
        assertEquals(1, walking.pauses, "建门暂停，施工跟着停下松手");
        builder.close(CloseReason.CANCELLED);
        assertEquals(1, walking.closes, "建门收尾，施工跟着收尾");
    }

    @Test
    void castingIsPausedStoppedAndClosedWithTheBuilder() {
        // 浇筑按动作推进：去舀水那一下在做时，建门的暂停、先到安全处再停、收尾都转到浇筑手上。
        DimensionFakes.FakeWorld view = new DimensionFakes.FakeWorld();
        view.blocks.putAll(CastingFakes.straightShore(SHORE, 2));
        view.feet = SHORE.cell(0, 1, 2);
        view.carried = PortalBuilder.FLINT_AND_STEEL;
        CastingFakes site = new CastingFakes(view.blocks);
        site.allowFlowAround(SHORE);
        site.feet = view.feet;
        site.give(CastingFakes.BUCKET, 1);
        site.give(CastingFakes.COBBLESTONE, 16);
        view.stacks.addAll(site.stacks());
        view.enqueueScan(new DimensionWorldView.Scan(List.of(), true));
        view.enqueueScan(new DimensionWorldView.Scan(SHORE.startRow(), true));
        HeldAction scooping = new HeldAction();
        // 身上只有空桶：浇筑先去舀一桶水，这一下停在半路。
        ItemNeeds needs = (request, permissions) -> CastingFakes.WATER_BUCKET.equals(request.wanted().itemId())
                ? scooping : site.actionFor(request, permissions);
        PortalBuilder builder = new PortalBuilder(Permissions.DEFAULT, 128, new DimensionTravelServices(
                view, () -> new DimensionFakes.FakeContext(), new DimensionFakes.FakeWalks(), needs,
                new DimensionFakes.FakeToMainhand(), new DimensionFakes.FakeUses(),
                permissions -> site.services(), null, () -> List.of(), progress -> {}));
        runUntilBusy(builder, scooping);

        assertEquals(Interruptibility.UNSAFE_TO_STOP, builder.interruptibility(tick()),
                "浇筑手上那一下停不得，建门就停不得");
        assertTrue(builder.stopSafely(), "叫建门先到安全处再停，转给浇筑去收尾");
        assertEquals(1, scooping.stops, "浇筑手上那一下收到先停稳");
        assertFalse(builder.stoppedSafely(), "那一下还没停稳，建门也没停稳");
        builder.pause();
        assertEquals(1, scooping.pauses, "建门暂停，浇筑跟着停下松手");
        builder.close(CloseReason.CANCELLED);
        assertEquals(1, scooping.closes, "建门收尾，浇筑跟着收尾");
    }

    /** 推进建门直到子任务手上那一下开始做；途中收场说明没走到那一步。 */
    private static void runUntilBusy(PortalBuilder builder, HeldAction held) {
        builder.start(tick());
        for (int i = 0; i < 400 && held.ticks == 0; i++) {
            assertFalse(builder.tick(tick()) instanceof TickResult.Finished, "建门没走到那一下就收场了");
        }
        assertTrue(held.ticks > 0, "走到了那一下");
    }

    /** 一直在做、停不得的一下（走着、舀着水）：记下被推进、叫停稳、暂停、收尾了几次。 */
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

    private static Function<BlockPos, Optional<BlockState>> readOf(
            Map<BlockPos, BlockState> world) {
        return cell -> Optional.ofNullable(world.get(cell));
    }

    /** 施工引擎的离线替身：一格一刻放好，写进与世界视图共用的那张方块表。 */
    private static final class ConstructionFakes implements ConstructionSeams.ReadsSite,
            ConstructionSeams.PlansPlacement, ConstructionSeams.Clicks, DigsBlocks, ItemNeeds,
            ConstructionSeams.HoldsItem, ConstructionSeams.Guards, ConstructionSeams.Ledger,
            BringsPlayerClose {
        final Map<BlockPos, BlockState> world;
        final List<BlockPos> placed = new ArrayList<>();
        final List<BlockPos> temporaries = new ArrayList<>();
        boolean failPlacing;
        /** 每次放置后的现场改动：模拟施工期间掉进门里的东西。 */
        Runnable afterPlace;
        /** 走近时交出的动作；没给就一刻走到。 */
        Action approach;

        ConstructionFakes(Map<BlockPos, BlockState> world) {
            this.world = world;
        }

        @Override public boolean loaded(BlockPos pos) { return true; }
        @Override public BlockState state(BlockPos pos) { return world.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
        @Override public String dimension() { return "minecraft:overworld"; }
        @Override public BlockPos feet() { return new BlockPos(8, 65, 8); }
        @Override public int carried(String itemId) { return 64; }
        @Override public boolean creative() { return false; }
        @Override public boolean unbreakable(BlockPos pos) { return false; }
        @Override public Optional<String> temporaryMaterial() { return Optional.of("minecraft:cobblestone"); }

        @Override public Optional<PlacementPrediction.Placement> predict(PlannedCell cell, BlockPos clicked,
                Direction face) {
            return Optional.of(new PlacementPrediction.Placement(cell.pos(), Direction.UP, cell.state()));
        }

        @Override public boolean requiresSneak(BlockPos clicked) { return false; }

        @Override public Click place(PlannedCell cell, PlacementPrediction.Placement placement, boolean sneak,
                PlacementConfirmation confirmation) {
            return click(() -> {
                if (!failPlacing) {
                    world.put(cell.pos(), placement.predicted());
                    placed.add(cell.pos());
                }
                if (afterPlace != null) {
                    afterPlace.run();
                }
            });
        }

        @Override public Click pour(PlannedCell cell, InteractionConfirmation confirmation) {
            return click(() -> world.put(cell.pos(), cell.state()));
        }

        @Override public Click scoop(BlockPos source, InteractionConfirmation confirmation) {
            return click(() -> world.put(source, Blocks.AIR.defaultBlockState()));
        }

        @Override public Click use(BlockPos block, InteractionConfirmation confirmation) {
            return click(() -> { });
        }

        private Click click(Runnable effect) {
            return new Click(immediate(effect), () -> InteractionResult.applied("替身确认"));
        }

        @Override public Optional<Action> dig(BlockPos target) {
            return Optional.of(immediate(() -> world.put(target, Blocks.AIR.defaultBlockState())));
        }

        @Override public Action actionFor(ItemRequest request, Permissions permissions) {
            return immediate(() -> { });
        }

        @Override public ConstructionSeams.HoldPlan hold(String itemId) {
            return new ConstructionSeams.HoldPlan.Ready();
        }

        @Override public Optional<Problem> allows(PermissionCheck.WorldAction action, BlockPos pos, String blockType) {
            return Optional.empty();
        }

        @Override public void siteStarted(Blueprint blueprint) { }
        // 垫脚的临时方块记在账上：施工收尾才知道收哪几块（顶行没有可点的面时引擎会先垫一块）。
        @Override public void temporaryPlaced(BlockPos pos, String blockType, String purpose) { temporaries.add(pos); }
        @Override public void temporaryRemoved(BlockPos pos) { temporaries.remove(pos); }
        @Override public List<BlockPos> temporaries() { return List.copyOf(temporaries); }
        @Override public Action toward(ApproachTarget target, Permissions permissions) {
            return approach != null ? approach : immediate(() -> { });
        }

        ConstructionServices services() {
            return new ConstructionServices(this, this, this, this, this, this, this, this, this, null);
        }

        private Action immediate(Runnable effect) {
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    effect.run();
                    return ActionStatus.done();
                }

                @Override public String describe() {
                    return "替身动作";
                }
            };
        }
    }
}
