// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.travel.spi.OfferSink;
import org.maiwithu.maicraft.behavior.travel.spi.TravelOffer;
import org.maiwithu.maicraft.behavior.travel.spi.TravelRequest;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;
import org.maiwithu.maicraft.kernel.goal.Target;

/**
 * 过折跃门：报价只在末地、隔着虚空时非它不可、要稀有品许可；一段里先备珍珠、走到站位、扔、等落到虚空另一边，
 * 扔歪了记一笔再扔一次，两次都歪就交回；站着看不见门洞换站位不算扔过。
 */
class PearlCrossingTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final String PEARL = PearlCrossingLeg.PEARL;
    /** 主岛那一圈正东那扇门，和正南那扇门。 */
    private static final BlockPos EAST_GATE = new BlockPos(96, 75, 0);
    private static final BlockPos SOUTH_GATE = new BlockPos(-1, 75, 96);
    /** 正东那扇门旁有人垫好的落脚处：门东边三格、脚低一格。 */
    private static final BlockPos STANCE = EAST_GATE.offset(3, -1, 0);
    private static final Permissions RARE = Permissions.DEFAULT.mergedWith(null, null, true, null, null, null);

    private final DimensionFakes.FakeWorld world = new DimensionFakes.FakeWorld();
    private final DimensionFakes.FakeWalks walks = new DimensionFakes.FakeWalks();
    private final DimensionFakes.FakeToMainhand hands = new DimensionFakes.FakeToMainhand();
    private final PearlSupply supply = new PearlSupply();
    private final Throws throwing = new Throws();
    private final DimensionTravelServices services = new DimensionTravelServices(world, () -> null, walks, supply,
            hands, throwing, null, null, null, null);
    private final TravelFakes.TestTick tick = new TravelFakes.TestTick();

    PearlCrossingTest() {
        world.dimension = DimensionRoute.END;
        world.feet = STANCE;
        gateAt(EAST_GATE);
        world.blocks.put(STANCE.below(), Blocks.END_STONE.defaultBlockState());
    }

    /** 摆一扇原版样子的折跃门：四周一片空气，门上下一格十字形基岩、再上下一格一块基岩。 */
    private void gateAt(BlockPos gate) {
        for (int dx = -6; dx <= 6; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -6; dz <= 6; dz++) {
                    world.blocks.putIfAbsent(gate.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
                }
            }
        }
        world.blocks.put(gate, Blocks.END_GATEWAY.defaultBlockState());
        for (int dy : new int[] {-1, 1}) {
            for (BlockPos arm : List.of(gate, gate.east(), gate.west(), gate.north(), gate.south())) {
                world.blocks.put(arm.offset(0, dy, 0), Blocks.BEDROCK.defaultBlockState());
            }
        }
        world.blocks.put(gate.above(2), Blocks.BEDROCK.defaultBlockState());
        world.blocks.put(gate.below(2), Blocks.BEDROCK.defaultBlockState());
    }

    private void carryPearls(int count) {
        world.stacks.removeIf(stack -> PEARL.equals(stack.itemId()));
        if (count > 0) world.stacks.add(new BackpackStack(PEARL, count, 16, false, false, true, false));
    }

    private int pearls() {
        return world.stacks.stream().filter(stack -> PEARL.equals(stack.itemId())).mapToInt(BackpackStack::count).sum();
    }

    private TravelRequest request(BlockPos to, Permissions permissions, String via) {
        return new TravelRequest(world.feet, to, world.dimension, 2, permissions, Map.of(), via);
    }

    private Answers ask(TravelRequest request) {
        Answers answers = new Answers();
        new PearlCrossing(services).offer(request, answers);
        return answers;
    }

    /** 推进一段到结束，最多这么多刻。 */
    private ActionStatus run(Action leg, int maxTicks) {
        for (int i = 0; i < maxTicks; i++) {
            ActionStatus status = leg.tick(tick);
            tick.nextTick();
            if (!(status instanceof ActionStatus.Running)) return status;
        }
        throw new AssertionError(maxTicks + " 刻内没有结束：" + leg.describe());
    }

    @Test
    void 不在末地_两头同一片_都不出报价() {
        world.dimension = DimensionRoute.OVERWORLD;
        Answers overworld = ask(request(new BlockPos(1100, 64, 0), RARE, null));
        assertEquals(Problem.Kind.UNSUPPORTED, overworld.declinedKind);
        assertTrue(overworld.reason.contains("只在末地"), overworld.reason);

        world.dimension = DimensionRoute.END;
        Answers sameSide = ask(request(new BlockPos(20, 64, 20), RARE, PearlCrossing.VIA.name()));
        assertEquals(Problem.Kind.UNSUPPORTED, sameSide.declinedKind);
        assertTrue(sameSide.reason.contains("两头都在主岛"), sameSide.reason);
        assertFalse(new PearlCrossing(services).claims(request(new BlockPos(20, 64, 20), RARE, null)));
    }

    @Test
    void 不许用稀有品_说清要开_use_rare_items() {
        Answers answers = ask(request(new BlockPos(1100, 64, 0), Permissions.DEFAULT, null));
        assertEquals(Problem.Kind.NEED_APPROVAL, answers.declinedKind);
        assertTrue(answers.reason.contains("use_rare_items"), answers.reason);
        assertTrue(answers.offers.isEmpty());
    }

    @Test
    void 隔着虚空非它不可_每扇活门一份报价_起点是门() {
        gateAt(SOUTH_GATE);
        PearlCrossing mode = new PearlCrossing(services);
        TravelRequest across = request(new BlockPos(1100, 64, 0), RARE, null);
        assertTrue(mode.claims(across), "主岛去外岛走路过不去");
        assertTrue(mode.worksIn(DimensionRoute.END));
        assertFalse(mode.worksIn(DimensionRoute.OVERWORLD));
        assertTrue(mode.goesWithoutDestination());

        Answers answers = ask(across);
        assertNull(answers.declinedKind);
        assertEquals(List.of(EAST_GATE, SOUTH_GATE), answers.offers.stream().map(TravelOffer::from).toList(),
                "离人近的在前，起点写门的位置");
        assertEquals(EndIslands.exitEstimate(EAST_GATE), answers.offers.get(0).to());
        // 行程挑过完门离目的地最近的那扇：正东那扇送到 (1024, 64, 0)，正南那扇送到 (0, 64, 1024)。
        assertTrue(TravelPlanner.total(answers.offers.get(0), new BlockPos(1100, 64, 0))
                < TravelPlanner.total(answers.offers.get(1), new BlockPos(1100, 64, 0)));
    }

    @Test
    void 门没扫完不答_扫完没有就说清看没看到那一圈() {
        world.blocks.clear();
        world.enqueueScan(new DimensionWorldView.Scan(List.of(), false));
        Answers waiting = ask(request(new BlockPos(1100, 64, 0), RARE, null));
        assertTrue(waiting.offers.isEmpty());
        assertNull(waiting.declinedKind, "没扫完不下没有的结论");

        Answers unseen = ask(request(new BlockPos(1100, 64, 0), RARE, null));
        assertEquals(Problem.Kind.NOT_FOUND, unseen.declinedKind);
        assertTrue(unseen.reason.contains("没加载"), unseen.reason);

        for (BlockPos slot : EndIslands.ringSlots()) world.blocks.put(slot, Blocks.AIR.defaultBlockState());
        Answers none = ask(request(new BlockPos(1100, 64, 0), RARE, null));
        assertTrue(none.reason.contains("一扇折跃门都没有"), none.reason);
    }

    @Test
    void 身上没珍珠先去弄一颗_扔一次就到了外岛_落地站稳() {
        carryPearls(0);
        throwing.plan.add(new Throw(null, () -> {
            carryPearls(pearls() - 1);
            world.feet = new BlockPos(1030, 62, 4);
        }));
        PearlCrossingLeg leg = new PearlCrossingLeg(EAST_GATE, RARE, services);

        assertInstanceOf(ActionStatus.Done.class, run(leg, 100));
        assertEquals(1, supply.asked.size(), "缺珍珠交拿东西的引擎弄一颗");
        assertEquals(1, supply.asked.get(0).count());
        assertEquals(List.of(PEARL), hands.moved);
        assertEquals(List.of(EAST_GATE), throwing.aimedAt);
        assertTrue(walks.started.isEmpty(), "本来就站在能扔的落脚处，不用走");
        assertEquals(1, leg.changes().stream().filter(change -> change.kind() == Change.Kind.ITEM_CONSUMED).count());
        assertTrue(leg.summary().contains("落在外岛"), leg.summary());
    }

    @Test
    void 扔歪一次_记一笔再扔一次过去了() {
        carryPearls(2);
        throwing.plan.add(new Throw(null, () -> carryPearls(pearls() - 1)));
        throwing.plan.add(new Throw(null, () -> {
            carryPearls(pearls() - 1);
            world.feet = new BlockPos(1030, 62, 4);
        }));
        PearlCrossingLeg leg = new PearlCrossingLeg(EAST_GATE, RARE, services);

        assertInstanceOf(ActionStatus.Done.class, run(leg, PearlCrossingLeg.WAIT_TICKS + 100));
        assertEquals(2, throwing.aimedAt.size());
        assertTrue(supply.asked.isEmpty(), "身上还有一颗，不用去弄");
        assertTrue(leg.summary().contains("第 1 颗珍珠从"), leg.summary());
        assertEquals(2, leg.changes().size(), "两颗珍珠都扔掉了");
    }

    @Test
    void 两次都没进门_交回走不通_两次经过都写上() {
        carryPearls(5);
        throwing.plan.add(new Throw(null, () -> carryPearls(pearls() - 1)));
        throwing.plan.add(new Throw(null, () -> carryPearls(pearls() - 1)));
        PearlCrossingLeg leg = new PearlCrossingLeg(EAST_GATE, RARE, services);

        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class,
                run(leg, 2 * PearlCrossingLeg.WAIT_TICKS + 100));
        assertEquals(Problem.Kind.UNREACHABLE, failed.problem().kind());
        String message = failed.problem().message();
        assertTrue(message.contains("第 1 颗") && message.contains("第 2 颗"), message);
        assertTrue(message.length() < 400, "两次经过各一句，长度有界：" + message.length());
        assertEquals(3, pearls(), "第三颗不扔");
    }

    @Test
    void 站着看不见门洞_换个站位再扔_不算扔过() {
        carryPearls(1);
        throwing.plan.add(new Throw(Problem.of(Problem.Kind.UNREACHABLE, "从当前位置看不到门洞"), null));
        throwing.plan.add(new Throw(null, () -> {
            carryPearls(0);
            world.feet = new BlockPos(1030, 62, 4);
        }));
        PearlCrossingLeg leg = new PearlCrossingLeg(EAST_GATE, RARE, services);

        assertInstanceOf(ActionStatus.Done.class, run(leg, 100));
        assertEquals(1, walks.started.size(), "换站位走了一趟");
        assertTrue(leg.summary().startsWith("过折跃门 " + EAST_GATE.toShortString() + "：扔了 1 颗"), leg.summary());
    }

    @Test
    void 不许垫方块又没有现成落脚处_说要放开许可垫方块() {
        world.blocks.remove(STANCE.below());
        world.feet = new BlockPos(80, 60, 0);
        carryPearls(1);
        Permissions noBlocks = RARE.mergedWith(Permissions.BlockChanges.NONE, null, null, null, null, null);
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class,
                run(new PearlCrossingLeg(EAST_GATE, noBlocks, services), 20));
        assertEquals(Problem.Kind.UNREACHABLE, failed.problem().kind());
        assertTrue(failed.problem().message().contains("change_blocks=none"), failed.problem().message());

        // 许可垫方块：把要垫一块才站得住的脚位一起交给走到。
        assertInstanceOf(ActionStatus.Running.class, new PearlCrossingLeg(EAST_GATE, RARE, services).tick(tick));
        PearlCrossingLeg leg = new PearlCrossingLeg(EAST_GATE, RARE, services);
        leg.tick(tick);
        leg.tick(tick);
        assertEquals(1, walks.started.size());
    }

    @Test
    void 珍珠弄不到_如实结束这一段() {
        carryPearls(0);
        supply.failure = Problem.of(Problem.Kind.NEED_ITEM, "附近没有末影人，也没有存着珍珠的箱子");
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class,
                run(new PearlCrossingLeg(EAST_GATE, RARE, services), 20));
        assertEquals(Problem.Kind.NEED_ITEM, failed.problem().kind());
        assertTrue(failed.problem().message().startsWith("备末影珍珠没成"), failed.problem().message());
    }

    @Test
    void 等传送时停下不安全() {
        carryPearls(1);
        throwing.plan.add(new Throw(null, () -> carryPearls(0)));
        PearlCrossingLeg leg = new PearlCrossingLeg(EAST_GATE, RARE, services);
        for (int i = 0; i < 10; i++) {
            leg.tick(tick);
            tick.nextTick();
        }
        assertEquals(Interruptibility.UNSAFE_TO_STOP, leg.interruptibility());
    }

    @Test
    void 行程里隔着虚空只问它_挑朝着目的地的门_过去后走完剩下的路() {
        gateAt(SOUTH_GATE);
        carryPearls(1);
        throwing.plan.add(new Throw(null, () -> {
            carryPearls(0);
            world.feet = new BlockPos(1030, 62, 4);
        }));
        List<TravelDestination> walked = new ArrayList<>();
        TaskResult result = runJourney(new BlockPos(1100, 64, 0), walked, 200);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(List.of(EAST_GATE), throwing.aimedAt, "正东那扇送到的地方离目的地近");
        assertEquals(1, walked.size(), "过门后走完剩下的路");
        JourneyDetails details = assertInstanceOf(JourneyDetails.class, result.details());
        assertEquals(List.of("end_gateway", "walk"), details.legs().stream().map(JourneyDetails.Leg::via).toList());
    }

    @Test
    void 一扇门扔两颗都没过去_行程换下一扇() {
        gateAt(SOUTH_GATE);
        carryPearls(3);
        throwing.plan.add(new Throw(null, () -> carryPearls(pearls() - 1)));
        throwing.plan.add(new Throw(null, () -> carryPearls(pearls() - 1)));
        throwing.plan.add(new Throw(null, () -> {
            carryPearls(pearls() - 1);
            world.feet = new BlockPos(1030, 62, 4);
        }));
        List<TravelDestination> walked = new ArrayList<>();
        TaskResult result = runJourney(new BlockPos(1100, 64, 0), walked, 3 * PearlCrossingLeg.WAIT_TICKS + 300);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(List.of(EAST_GATE, EAST_GATE, SOUTH_GATE), throwing.aimedAt);
        JourneyDetails details = assertInstanceOf(JourneyDetails.class, result.details());
        assertEquals(List.of("end_gateway", "end_gateway", "walk"),
                details.legs().stream().map(JourneyDetails.Leg::via).toList());
        assertTrue(result.attempts().stream().anyMatch(attempt -> attempt.result().contains("都没过去")),
                "没过成的那扇门记进试过的办法");
    }

    /** 在主岛上跑一趟去外岛某处的行程：只登记折跃门这一种方式，走路段一刻就到。 */
    private TaskResult runJourney(BlockPos to, List<TravelDestination> walked, int maxTicks) {
        TravelWorldView spots = new TravelWorldView() {
            @Override public WorldPosition currentSpot() {
                return new WorldPosition(world.feet.getX(), world.feet.getY(), world.feet.getZ(), world.dimension);
            }

            @Override public Target.Toward currentFacing() {
                return Target.Toward.NORTH;
            }
        };
        JourneyTask journey = new JourneyTask(new JourneyTask.Journey(
                TravelDestination.confirmed(new WorldPosition(to.getX(), to.getY(), to.getZ(), DimensionRoute.END), 2),
                null, Map.of(), RARE, 0), spots, List.of(new PearlCrossing(services)), destination -> {
                    walked.add(destination);
                    return new ArrivingWalk();
                });
        journey.start(tick);
        for (int i = 0; i < maxTicks; i++) {
            if (journey.tick(tick) instanceof TickResult.Finished finished) return finished.result();
            tick.nextTick();
        }
        throw new AssertionError(maxTicks + " 刻内行程没有结束：" + journey.describe());
    }

    /** 收报价的口：记下报价，或说送不了的原因。 */
    private static final class Answers implements OfferSink {
        final List<TravelOffer> offers = new ArrayList<>();
        Problem.Kind declinedKind;
        String reason = "";

        @Override public void offer(TravelOffer offer) {
            offers.add(offer);
        }

        @Override public void decline(Problem.Kind kind, String reason) {
            this.declinedKind = kind;
            this.reason = reason;
        }
    }

    /** 一次扔：要么瞄不上（带问题），要么扔出去，扔出去之后现场怎么变由测试摆。 */
    private record Throw(Problem refused, Runnable effect) {}

    /** 朝一格扔东西的替身：按剧本一次一次扔，记下对着哪一格。 */
    private static final class Throws implements UsesBlocks {
        final List<BlockPos> aimedAt = new ArrayList<>();
        final Deque<Throw> plan = new ArrayDeque<>();

        @Override public Action useBlock(BlockPos target, InteractionConfirmation confirmation) {
            throw new AssertionError("过折跃门不右键方块");
        }

        @Override public Action useHeldItemToward(BlockPos target, InteractionConfirmation confirmation) {
            aimedAt.add(target);
            Throw next = plan.isEmpty() ? new Throw(null, () -> {}) : plan.remove();
            return next.refused() != null ? DimensionFakes.FakeObtain.failing(next.refused())
                    : DimensionFakes.FakeObtain.immediate(next.effect());
        }
    }

    /** 拿珍珠的替身：弄到一颗就放进背包；也可以按给定问题弄不到。 */
    private final class PearlSupply implements ItemNeeds {
        final List<ItemRequest> asked = new ArrayList<>();
        Problem failure;

        @Override public Action actionFor(ItemRequest request, Permissions permissions) {
            asked.add(request);
            if (failure != null) return DimensionFakes.FakeObtain.failing(failure);
            return DimensionFakes.FakeObtain.immediate(() -> carryPearls(pearls() + request.count()));
        }
    }

    /** 走路段替身：一刻就到。 */
    private static final class ArrivingWalk implements Task {
        @Override public TickResult tick(TickContext context) {
            return TickResult.finished(TaskResult.done("出行：到了"));
        }

        @Override public void pause() {}

        @Override public TaskResult close(CloseReason reason) {
            return TaskResult.done("出行：到了");
        }

        @Override public String describe() {
            return "走路替身";
        }
    }
}
