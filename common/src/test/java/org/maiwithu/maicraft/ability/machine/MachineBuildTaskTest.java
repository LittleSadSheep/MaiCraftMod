// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.ability.machine.spi.Installation;
import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.ability.machine.spi.PartCell;
import org.maiwithu.maicraft.behavior.acquire.DigsBlocks;
import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.construction.ConstructionDetails;
import org.maiwithu.maicraft.behavior.construction.ConstructionSeams;
import org.maiwithu.maicraft.behavior.construction.ConstructionServices;
import org.maiwithu.maicraft.behavior.construction.PlacementConfirmation;
import org.maiwithu.maicraft.behavior.construction.PlacementPrediction;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.behavior.interaction.InteractionResult;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.recipe.GameRecipeTable;
import org.maiwithu.maicraft.behavior.recipe.RecipeLookup;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;
import java.time.Instant;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryKind;
import org.maiwithu.maicraft.behavior.construction.Blueprint;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 按机器蓝图施工的任务：已满足一个动作不做、施工与安装段部件设置按顺序做、机器的格引擎不放、
 * 施工缺料不回滚已放的、拆整台逐格挖并标档案、补丁并进档案。
 */
class MachineBuildTaskTest {

    private static final String DIM = "minecraft:overworld";
    private static final BlockPos ANCHOR = new BlockPos(100, 64, 100);
    private static BlockState FURNACE;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        FURNACE = Blocks.FURNACE.defaultBlockState();
    }

    /** 一份最小蓝图：锚点上一格熔炉；参数按需要加部件、安装段与设置。 */
    private static MachineBlueprint blueprint(MachineBlueprint.Part part, MachineBlueprint.Segment segment,
            MachineBlueprint.Setting setting) {
        return new MachineBlueprint(List.of(PlannedCell.block(new BlockPos(0, 0, 0), FURNACE, Set.of())),
                part == null ? List.of() : List.of(part),
                segment == null ? List.of() : List.of(segment),
                setting == null ? List.of() : List.of(setting),
                List.of());
    }

    private static MachineBuildInput buildInput(MachineBlueprint merged, MachineArchive archive, String name) {
        return new MachineBuildInput(false, merged, new WorldPosition(ANCHOR.getX(), ANCHOR.getY(),
                ANCHOR.getZ(), DIM), name, archive, Permissions.DEFAULT, "测试施工");
    }

    @Test
    void 开始时已和蓝图一致_一个动作都不做() {
        Rig rig = new Rig(tempDir);
        MachineBlueprint plan = blueprint(null, null, new MachineBlueprint.Setting(BlockPos.ZERO, "mode", "fast"));
        rig.type.settingValues.put("mode", "fast");
        rig.world.blocks.put(ANCHOR, FURNACE);
        MachineArchive archive = MachineArchive.fresh("压机", DIM, ANCHOR, plan, null);
        TaskResult result = rig.run(buildInput(plan, archive, "压机"));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertTrue(result.changes().isEmpty(), "没动手就不该有变化");
        assertTrue(rig.type.touched.isEmpty(), "一个动作都不做");
        assertEquals("压机", ((MachineBuildDetails) result.details()).archiveName());
    }

    @Test
    void 施工完了装段再装部件再改设置() {
        Rig rig = new Rig(tempDir);
        MachineBlueprint.Segment segment = new MachineBlueprint.Segment("test:belt",
                List.of(new BlockPos(0, 1, 0)));
        MachineBlueprint.Part part = new MachineBlueprint.Part(BlockPos.ZERO, Direction.NORTH, "test:part");
        MachineBlueprint.Setting setting = new MachineBlueprint.Setting(BlockPos.ZERO, "mode", "fast");
        MachineBlueprint plan = blueprint(part, segment, setting);
        rig.type.installationOk = installation -> true;
        rig.type.partOk = partCell -> true;
        rig.type.installActions.add(new MachineTestDoubles.Scripted(1,
                () -> rig.type.installedOk = installation -> true));
        rig.type.mountActions.add(new MachineTestDoubles.Scripted(1,
                () -> rig.type.mountedOk = partCell -> true));
        rig.type.settingValues.put("mode", "slow");
        rig.type.onChange.put("mode", () -> rig.type.settingValues.put("mode", "fast"));
        TaskResult result = rig.run(buildInput(plan, null, "压机"));
        assertEquals(TaskResult.Status.DONE, result.status(),
                result.summary() + " / 问题:" + result.problem() + " / 试过:" + result.attempts());
        MachineBuildDetails details = (MachineBuildDetails) result.details();
        assertTrue(details.installations().get(0).installed(), result.summary());
        assertTrue(details.parts().get(0).mounted(), result.summary());
        assertTrue(details.settings().get(0).applied(), result.summary());
        assertEquals("fast", details.settings().get(0).readback());
        assertEquals(List.of("install:test:belt", "mount:test:part", "change:mode"), rig.type.touched,
                "安装段 → 部件 → 设置，按顺序做");
        assertEquals(1, details.cells().getOrDefault("checked_by_machine", 0), "安装段的格由机器核对");
        assertEquals(1, details.cells().getOrDefault("placed", 0), "普通格由引擎放");
        assertEquals(FURNACE, rig.world.blocks.get(ANCHOR));
        // 建成入档：蓝图与位置对上，世界记忆里能看到这台机器。
        MachineArchive stored = rig.archives.find("压机").orElseThrow();
        assertEquals(plan, stored.blueprint());
        assertEquals(ANCHOR, stored.anchor());
        assertTrue(rig.memory.allRecords().stream().anyMatch(record ->
                record.kind() == MemoryKind.MACHINE
                        && "压机".equals(record.blockType())));
    }

    @Test
    void 施工缺料_已放的不回滚按缺料收场() {
Rig rig = new Rig(tempDir);
        rig.site.fail = true;
        MachineBlueprint plan = blueprint(null, null, null);
        TaskResult result = rig.run(buildInput(plan, null, "压机"));
        assertEquals(TaskResult.Status.PARTIAL, result.status(), result.summary());
        assertEquals(Problem.Kind.NEED_ITEM, result.problem().kind());
        assertTrue(rig.world.blocks.get(ANCHOR) == null || rig.world.blocks.get(ANCHOR).isAir(),
                "没放成就是没放成，不冒充");
    }

    @Test
    void 拆整台_逐格挖掉档案标拆除() {
        Rig rig = new Rig(tempDir);
        MachineBlueprint plan = blueprint(null, null, null);
        rig.world.blocks.put(ANCHOR, FURNACE);
        MachineArchive archive = MachineArchive.fresh("旧压机", DIM, ANCHOR, plan, null);
        rig.archives.save(archive);
        rig.memory.rememberMachine(new WorldPosition(ANCHOR.getX(), ANCHOR.getY(), ANCHOR.getZ(), DIM),
                "旧压机", Instant.now());
        TaskResult result = rig.run(new MachineBuildInput(true, null,
                new WorldPosition(ANCHOR.getX(), ANCHOR.getY(), ANCHOR.getZ(), DIM), "旧压机", archive,
                Permissions.DEFAULT, "拆掉机器"));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertTrue(rig.world.blocks.get(ANCHOR).isAir(), "机器格被挖掉");
        assertTrue(rig.archives.find("旧压机").orElseThrow().removed(), "档案标为已拆除，蓝图保留");
        assertTrue(rig.memory.allRecords().stream().noneMatch(record ->
                record.kind() == MemoryKind.MACHINE), "拆掉的机器从记忆抹掉");
    }

    @Test
    void 补丁并进已有档案_按档案锚点施工() {
        Rig rig = new Rig(tempDir);
        MachineBlueprint original = blueprint(null, null, null);
        rig.world.blocks.put(ANCHOR, FURNACE);
        MachineArchive archive = MachineArchive.fresh("压机", DIM, ANCHOR, original, null);
        MachineBlueprint added = new MachineBlueprint(
                List.of(PlannedCell.block(new BlockPos(1, 0, 0), Blocks.STONE.defaultBlockState(), Set.of())),
                List.of(), List.of(), List.of(), List.of());
        MachineBlueprint merged = MachinePatch.merge(original, added);
        TaskResult result = rig.run(buildInput(merged, archive, "压机"));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertNotNull(rig.world.blocks.get(ANCHOR.offset(new BlockPos(1, 0, 0))), "新增的格放出来了");
        assertEquals(2, rig.archives.find("压机").orElseThrow().blueprint().cells().size(), "档案并入了补丁");
    }

    @Test
    void 没有联动认安装段_照实记下不冒充建成() {
        Rig rig = new Rig(tempDir);
        MachineBlueprint.Segment segment = new MachineBlueprint.Segment("test:belt", List.of(new BlockPos(0, 1, 0)));
        MachineBlueprint plan = blueprint(null, segment, null);
        rig.type.installationOk = installation -> true;
        TaskResult result = rig.run(buildInput(plan, null, "压机"));
        assertEquals(TaskResult.Status.PARTIAL, result.status(), result.summary());
        MachineBuildDetails details = (MachineBuildDetails) result.details();
        assertFalse(details.installations().get(0).installed());
        assertTrue(details.installations().get(0).note().contains("没有机器类型认"), result.summary());
    }

    /** 一套离线替身：现场与施工引擎的接缝全在一个假对象上，什么都能一刻做完。 */
    private static final class FakeSite implements ConstructionSeams.ReadsSite, ConstructionSeams.PlansPlacement,
            ConstructionSeams.Clicks, DigsBlocks, ItemNeeds, ConstructionSeams.HoldsItem, ConstructionSeams.Guards,
            ConstructionSeams.Ledger, BringsPlayerClose {
        /** 与机器现场视图共用的同一张世界表：引擎放成什么，机器比对读到的就是什么。 */
        final Map<BlockPos, BlockState> world;
        final Map<String, Integer> carried = new HashMap<>();
        final List<BlockPos> temporaries = new ArrayList<>();
        boolean fail;

        FakeSite(Map<BlockPos, BlockState> sharedWorld) {
            this.world = sharedWorld;
        }

        @Override public boolean loaded(BlockPos pos) { return true; }
        @Override public BlockState state(BlockPos pos) { return world.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
        @Override public String dimension() { return DIM; }
        @Override public BlockPos feet() { return ANCHOR.west(); }
        @Override public int carried(String itemId) { return carried.getOrDefault(itemId, 0); }
        @Override public boolean creative() { return false; }
        @Override public boolean unbreakable(BlockPos pos) { return false; }
        @Override public Optional<String> temporaryMaterial() { return Optional.of("minecraft:cobblestone"); }

        @Override public Optional<PlacementPrediction.Placement> predict(PlannedCell cell, BlockPos clicked, Direction face) {
            return Optional.of(new PlacementPrediction.Placement(clicked, face, cell.state()));
        }

        @Override public boolean requiresSneak(BlockPos clicked) { return false; }

        @Override public Click place(PlannedCell cell, PlacementPrediction.Placement placement, boolean sneak,
                PlacementConfirmation confirmation) {
            return click(() -> world.put(cell.pos(), placement.predicted()));
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
            if (fail) {
                return failing("附近找不到 " + request.wanted().itemId());
            }
            return immediate(() -> carried.merge(request.wanted().itemId(), request.count(), Integer::sum));
        }

        @Override public ConstructionSeams.HoldPlan hold(String itemId) {
            return new ConstructionSeams.HoldPlan.Ready();
        }

        /** 一步就报失败的动作。 */
        private Action failing(String why) {
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    return ActionStatus.failed(Problem.of(Problem.Kind.NOT_FOUND, why, null));
                }

                @Override public String describe() {
                    return "替身失败";
                }
            };
        }

        @Override public Optional<Problem> allows(PermissionCheck.WorldAction action, BlockPos pos, String blockType) {
            return Optional.empty();
        }

        @Override public void siteStarted(Blueprint blueprint) { }
        @Override public void temporaryPlaced(BlockPos pos, String blockType, String purpose) { temporaries.add(pos); }
        @Override public void temporaryRemoved(BlockPos pos) { temporaries.remove(pos); }
        @Override public List<BlockPos> temporaries() { return List.copyOf(temporaries); }
        @Override public Action toward(ApproachTarget target, Permissions permissions) {
            return immediate(() -> { });
        }

        ConstructionServices services() {
            return new ConstructionServices(this, this, this, this, this, this, this, this, this, null);
        }
    }

    /** 一刻做完的动作。 */
    private record Immediate(Runnable effect) implements Action {
        @Override public ActionStatus tick(TickContext context) {
            effect.run();
            return ActionStatus.done();
        }

        @Override public String describe() { return "替身动作"; }
    }

    private static Immediate immediate(Runnable effect) {
        return new Immediate(effect);
    }

    /** 一套搭建好的环境：机器类型、现场视图、档案库、世界记忆与施工服务替身。 */
    private static final class Rig {
        final MachineTestDoubles.FakeMachineType type =
                new MachineTestDoubles.FakeMachineType("test:press", "测试压机", MachineRole.PROCESSING,
                        state -> state.is(Blocks.FURNACE));
        final MachineTestDoubles.FakeWorld world = new MachineTestDoubles.FakeWorld();
        final MachineTestDoubles.FakeClicks clicks = new MachineTestDoubles.FakeClicks();
        final FakeSite site = new FakeSite(world.blocks);
        final MachineArchives archives;
        final WorldMemory memory;
        final MachineServices services;

        Rig(Path tempDir) {
            world.dimension = DIM;
            world.playerAt = ANCHOR.west();
            // 脚下一片实地：站位判断要站得稳、看得见。
            for (int x = ANCHOR.getX() - 3; x <= ANCHOR.getX() + 3; x++) {
                for (int z = ANCHOR.getZ() - 3; z <= ANCHOR.getZ() + 3; z++) {
                    world.blocks.put(new BlockPos(x, ANCHOR.getY() - 1, z), Blocks.STONE.defaultBlockState());
                }
            }
            DocumentStore documents = new DocumentStore(tempDir.resolve("state.sqlite"));
            archives = new MachineArchives(documents, "world1");
            memory = new WorldMemory(documents, "world1");
            services = new MachineServices(() -> null, world, List.of(type), List.of(),
                    new MachineTestDoubles.FakeArea(), new RecipeLookup(List.of(), new GameRecipeTable(Optional::empty)),
                    site, site, clicks, new MachineTestDoubles.FakeSeen(), new MachineTestDoubles.FakePlaces());
        }

        TaskResult run(MachineBuildInput input) {
            MachineBuildTask task = new MachineBuildTask(input, services, archives, memory,
                    permissions -> site.services());
            task.start(tick());
            for (long i = 0; i < 20000; i++) {
                if (task.tick(tick()) instanceof TickResult.Finished finished) {
                    return finished.result();
                }
            }
            throw new AssertionError("两万刻都没做完");
        }

        private TickContext tick() {
            long now = ++ticks;
            return new TickContext() {
                @Override public long gameTick() {
                    return now;
                }

                @Override public PlayerContext player() {
                    return null;
                }
            };
        }

        private long ticks;
    }
}
