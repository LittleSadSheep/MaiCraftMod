// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.time.Instant;
import org.maiwithu.maicraft.game.player.PlayerContext;
import java.util.ArrayList;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 末地门房间：没有要塞线索如实说、插眼不看 use_rare_items（目标就是去末地）、眼不够按缺料结束、
 * 逐格插眼、插满门面出现才算开，结果写用了几只眼、还剩几只。
 */
class EndPortalRoomTaskTest {

    private static final BlockPos RING_CENTER = new BlockPos(40, 30, -20);
    private static final String EYE = "minecraft:ender_eye";

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

    /** 一扇完整的末地门框架：十二块朝内，眼按 given 插好。 */
    private static void layRing(Map<BlockPos, BlockState> world, Set<BlockPos> withEye) {
        EndPortalFrame ring = new EndPortalFrame(RING_CENTER);
        for (Map.Entry<BlockPos, Direction> entry : ring.frames().entrySet()) {
            world.put(entry.getKey(), Blocks.END_PORTAL_FRAME.defaultBlockState()
                    .setValue(EndPortalFrameBlock.FACING, entry.getValue())
                    .setValue(EndPortalFrameBlock.HAS_EYE, withEye.contains(entry.getKey())));
        }
        for (BlockPos cell : ring.interior()) {
            world.put(cell, Blocks.AIR.defaultBlockState());
        }
    }

    /** 身上带几只末影之眼。 */
    private static void carryEyes(DimensionFakes.FakeWorld world, int count) {
        world.stacks.removeIf(stack -> EYE.equals(stack.itemId()));
        if (count > 0) world.stacks.add(new BackpackStack(EYE, count, 16, false, false, true, false));
    }

    private static int eyesOn(DimensionFakes.FakeWorld world) {
        return world.stacks.stream().filter(stack -> EYE.equals(stack.itemId())).mapToInt(BackpackStack::count).sum();
    }

    private EndPortalRoomTask task(DimensionFakes.FakeWorld world, WorldMemory memory,
            DimensionFakes.FakeObtain obtain, DimensionFakes.FakeUses uses, Permissions permissions) {
        DimensionTravelServices services = new DimensionTravelServices(world, () -> new DimensionFakes.FakeContext(),
                new DimensionFakes.FakeWalks(), obtain, new DimensionFakes.FakeToMainhand(), uses,
                perms -> null, memory, () -> List.of(), progress -> {});
        return new EndPortalRoomTask(permissions, 128, services);
    }

    private static TaskResult run(EndPortalRoomTask task) {
        task.start(tick());
        for (long i = 0; i < 20000; i++) {
            if (task.tick(tick()) instanceof TickResult.Finished finished) {
                return finished.result();
            }
        }
        throw new AssertionError("两万刻都没做完");
    }

    @Test
    void noStrongholdLeadEndsWithNotFound(@TempDir Path dir) {
        WorldMemory memory = new WorldMemory(new DocumentStore(dir.resolve("m.sqlite")), "w1");
        DimensionFakes.FakeWorld world = new DimensionFakes.FakeWorld();
        TaskResult result = run(task(world, memory, new DimensionFakes.FakeObtain(),
                new DimensionFakes.FakeUses(), Permissions.DEFAULT));

        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.NOT_FOUND, result.problem().kind());
        assertTrue(result.problem().message().contains("线索"), result.problem().message());
    }

    @Test
    void eyesGoInWithoutAskingForRareItems(@TempDir Path dir) {
        WorldMemory memory = new WorldMemory(new DocumentStore(dir.resolve("m.sqlite")), "w1");
        memory.rememberSite(new WorldPosition(50, 30, -20, "minecraft:overworld"),
                List.of("这一片见过要塞"), Instant.now());
        DimensionFakes.FakeWorld world = new DimensionFakes.FakeWorld();
        layRing(world.blocks, Set.of());
        carryEyes(world, 14);
        world.enqueueScan(new DimensionWorldView.Scan(List.of(
                RING_CENTER.relative(Direction.NORTH, 2).relative(Direction.EAST)), true));
        // 每点一格插上那一格的眼、身上少一只；十二格插满原版把内侧换成门面。
        DimensionFakes.FakeUses uses = new DimensionFakes.FakeUses();
        EndPortalFrame ring = new EndPortalFrame(RING_CENTER);
        for (int i = 0; i < 12; i++) {
            uses.effects.add(() -> {
                BlockPos clicked = uses.clicked.get(uses.clicked.size() - 1);
                world.blocks.put(clicked, world.blocks.get(clicked).setValue(EndPortalFrameBlock.HAS_EYE, true));
                carryEyes(world, eyesOn(world) - 1);
                if (ring.frames().keySet().stream().allMatch(cell ->
                        world.blocks.get(cell).getValue(EndPortalFrameBlock.HAS_EYE))) {
                    ring.interior().forEach(cell -> world.blocks.put(cell, Blocks.END_PORTAL.defaultBlockState()));
                }
            });
        }
        DimensionFakes.FakeObtain obtain = new DimensionFakes.FakeObtain();
        // use_rare_items 关着：去末地本身就是目标，照样插。
        Permissions noRare = Permissions.DEFAULT.mergedWith(null, null, false, null, null, null);
        TaskResult result = run(task(world, memory, obtain, uses, noRare));

        assertEquals(TaskResult.Status.DONE, result.status(), result.summary() + " / " + result.problem());
        assertTrue(result.summary().contains("插了 12 只末影之眼，身上还剩 2 只"), result.summary());
        assertTrue(obtain.asked.isEmpty(), "身上的眼够插满，不该再去备：" + obtain.asked);
        assertTrue(result.changes().stream().anyMatch(change -> change.kind() == Change.Kind.ITEM_CONSUMED
                && EYE.equals(change.what()) && change.count() == 12), "用掉的眼记成一条消耗：" + result.changes());
    }

    @Test
    void tooFewEyesEndsAsMissingItem(@TempDir Path dir) {
        WorldMemory memory = new WorldMemory(new DocumentStore(dir.resolve("m.sqlite")), "w1");
        memory.rememberSite(new WorldPosition(50, 30, -20, "minecraft:overworld"),
                List.of("这一片见过要塞"), Instant.now());
        DimensionFakes.FakeWorld world = new DimensionFakes.FakeWorld();
        layRing(world.blocks, Set.of());
        carryEyes(world, 3);
        world.enqueueScan(new DimensionWorldView.Scan(List.of(
                RING_CENTER.relative(Direction.NORTH, 2).relative(Direction.EAST)), true));
        DimensionFakes.FakeObtain obtain = new DimensionFakes.FakeObtain();
        obtain.answers.add(Problem.of(Problem.Kind.NOT_FOUND, "附近找不到末影之眼，也合不出来"));
        TaskResult result = run(task(world, memory, obtain, new DimensionFakes.FakeUses(), Permissions.DEFAULT));

        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.NEED_ITEM, result.problem().kind());
        assertTrue(result.problem().message().contains("还缺 12 只，身上只有 3 只"), result.problem().message());
        assertTrue(result.problem().suggestion().contains("obtain"), result.problem().suggestion());
        // 只备差的那九只：请求的数量是"再多拿几件"。
        assertEquals(9, obtain.asked.get(0).count());
    }

    @Test
    void insertingAllEyesLightsThePortal(@TempDir Path dir) {
        WorldMemory memory = new WorldMemory(new DocumentStore(dir.resolve("m.sqlite")), "w1");
        memory.rememberSite(new WorldPosition(50, 30, -20, "minecraft:overworld"),
                List.of("这一片见过要塞"), Instant.now());
        DimensionFakes.FakeWorld world = new DimensionFakes.FakeWorld();
        layRing(world.blocks, Set.of());
        world.enqueueScan(new DimensionWorldView.Scan(List.of(
                RING_CENTER.relative(Direction.NORTH, 2).relative(Direction.EAST)), true));
        // 每插一格就把那一格的眼插上；最后一格插满后原版把内侧九格换成门面。
        DimensionFakes.FakeUses uses = new DimensionFakes.FakeUses();
        EndPortalFrame ring = new EndPortalFrame(RING_CENTER);
        uses.effects.add(() -> {
            for (BlockPos cell : ring.frames().keySet()) {
                BlockState state = world.blocks.get(cell);
                if (state.is(Blocks.END_PORTAL_FRAME)) {
                    world.blocks.put(cell, state.setValue(EndPortalFrameBlock.HAS_EYE, true));
                }
            }
            for (BlockPos cell : ring.interior()) {
                world.blocks.put(cell, Blocks.END_PORTAL.defaultBlockState());
            }
        });
        carryEyes(world, 12);
        TaskResult result = run(task(world, memory, new DimensionFakes.FakeObtain(),
                uses, Permissions.DEFAULT));

        assertEquals(TaskResult.Status.DONE, result.status(), result.summary() + " / " + result.problem());
        assertTrue(result.summary().contains("门面已出现"), result.summary());
    }

    @Test
    void alreadyOpenRingIsReportedWithoutInserting(@TempDir Path dir) {
        WorldMemory memory = new WorldMemory(new DocumentStore(dir.resolve("m.sqlite")), "w1");
        memory.rememberSite(new WorldPosition(50, 30, -20, "minecraft:overworld"),
                List.of("stronghold"), Instant.now());
        DimensionFakes.FakeWorld world = new DimensionFakes.FakeWorld();
        layRing(world.blocks, Set.of());
        EndPortalFrame ring = new EndPortalFrame(RING_CENTER);
        for (BlockPos cell : ring.interior()) {
            world.blocks.put(cell, Blocks.END_PORTAL.defaultBlockState());
        }
        world.enqueueScan(new DimensionWorldView.Scan(List.of(
                RING_CENTER.relative(Direction.NORTH, 2).relative(Direction.EAST)), true));
        TaskResult result = run(task(world, memory, new DimensionFakes.FakeObtain(),
                new DimensionFakes.FakeUses(), Permissions.DEFAULT));

        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertTrue(result.summary().contains("不用插眼"), result.summary());
    }

    @Test
    void eyesInsertOneByOneIntoEmptyFrames(@TempDir Path dir) {
        WorldMemory memory = new WorldMemory(new DocumentStore(dir.resolve("m.sqlite")), "w1");
        memory.rememberSite(new WorldPosition(50, 30, -20, "minecraft:overworld"),
                List.of("要塞"), Instant.now());
        DimensionFakes.FakeWorld world = new DimensionFakes.FakeWorld();
        // 十二块里先插好两块：只给缺的十格插眼。
        EndPortalFrame ring = new EndPortalFrame(RING_CENTER);
        List<BlockPos> frames = new ArrayList<>(ring.frames().keySet());
        layRing(world.blocks, Set.of(frames.get(0), frames.get(1)));
        world.enqueueScan(new DimensionWorldView.Scan(List.of(frames.get(2)), true));
        // 每插一格把刚点的那格的眼插上（不整圈激活）：门面没出现按没开如实收场，插过的眼留证。
        DimensionFakes.FakeUses uses = new DimensionFakes.FakeUses();
        uses.effects.add(() -> {
            BlockPos justClicked = uses.clicked.get(uses.clicked.size() - 1);
            BlockState state = world.blocks.get(justClicked);
            if (state.is(Blocks.END_PORTAL_FRAME) && !state.getValue(EndPortalFrameBlock.HAS_EYE)) {
                world.blocks.put(justClicked, state.setValue(EndPortalFrameBlock.HAS_EYE, true));
            }
        });
        carryEyes(world, 10);
        TaskResult result = run(task(world, memory, new DimensionFakes.FakeObtain(),
                uses, Permissions.DEFAULT));

        assertEquals(TaskResult.Status.PARTIAL, result.status(), result.summary());
        assertTrue(result.problem().message().contains("门面没有出现"), result.problem().message());
        // 十只空眼逐格插过，变化一条不少；用掉的十只另记一条消耗。
        assertEquals(10, result.changes().stream().filter(change -> change.kind() == Change.Kind.BLOCK_CHANGED)
                .filter(change -> change.what().contains("ender_eye")).count(), "插了十格眼：" + result.changes());
        assertTrue(result.summary().contains("插了 10 只末影之眼"), result.summary());
    }
}
