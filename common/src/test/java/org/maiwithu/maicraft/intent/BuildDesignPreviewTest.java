// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.preview.PreviewSession;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;
import org.maiwithu.maicraft.mcp.MaiCraftRuntimeFacade;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;
import org.maiwithu.maicraft.task.TaskState;
import sun.misc.Unsafe;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Supplier;

/** Real compiler path, with a publisher capture; any body/tool work is a regression. */
public final class BuildDesignPreviewTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Unsafe memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        FlatLevel level = (FlatLevel) memory.allocateInstance(FlatLevel.class);
        LocalPlayer player = (LocalPlayer) memory.allocateInstance(LocalPlayer.class);
        level.loaded = true;
        field(Level.class, "dimension").set(level, Level.OVERWORLD);
        field(LocalPlayer.class, "clientLevel").set(player, level);
        field(Entity.class, "level").set(player, level);
        field(Entity.class, "position").set(player, new Vec3(.5, 64, .5));
        field(Entity.class, "blockPosition").set(player, new BlockPos(0, 64, 0));
        field(Player.class, "inventory").set(player, new Inventory(player));
        // 预览只展示作者给出的方块；不依赖用途、风格或房屋模板生成额外结构。
        Goal design = goal(BuildDesignAdapter.ABILITY);
        SemanticGoalContract.validate(design, IntentRuntime.KNOWN_ABILITIES);
        AtomicReference<PreviewSession> published = new AtomicReference<>();
        IntentAction action = BuildDesignAdapter.design(design, player, null, session -> {
            published.set(session); return true;
        });
        check(action instanceof IntentAction.Report report && report.result().success(),
                "design must complete with a report, never a tool, native task or movement chain");
        PreviewSession session = published.get();
        check(session != null && !session.cells().isEmpty() && session.visible(), "frozen geometry must be published");
        check(session.designOnly() && !session.confirm() && session.decision() == PreviewSession.Decision.DESIGN_ONLY,
                "local confirm cannot turn a read-only design into construction authority");
        session.cancel(); session.visible(true);
        check(session.designOnly() && !session.confirm() && !session.visible(),
                "cancelled design retains its read-only identity and cannot be revived or confirmed");
        check(player.position().equals(new Vec3(.5, 64, .5)), "design may not move the player");
        check(player.getInventory().isEmpty(), "design may not acquire or consume materials");
        var constructor = IntentRuntime.class.getDeclaredConstructor(); constructor.setAccessible(true);
        IntentRuntime runtime = constructor.newInstance();
        field(IntentRuntime.class, "stateIdentity").set(runtime,
                new StateIdentity("0".repeat(64), Path.of("build/preview-test-unused")));
        var dispatch = MaiCraftRuntimeFacade.class.getDeclaredMethod("dispatchExecution",
                Goal.class, LocalPlayer.class, Supplier.class);
        dispatch.setAccessible(true);
        Field brainField = field(CompanionTickDispatcher.class, "brain");
        Object previousBrain = brainField.get(null);
        Class<?> brainType = Class.forName("org.maiwithu.maicraft.task.CompanionBrain");
        Class<?> slotType = Class.forName("org.maiwithu.maicraft.task.TaskSlot");
        Object existingBrain = memory.allocateInstance(brainType), existingSlot = memory.allocateInstance(slotType);
        var bodyRecord = new IntentTaskRecord(UUID.randomUUID(), null, goal("maicraft:build"));
        bodyRecord.setState(TaskState.RUNNING);
        field(slotType, "record").set(existingSlot, bodyRecord);
        field(brainType, "current").set(existingBrain, existingSlot);
        brainField.set(null, existingBrain);
        // Invoke the public execute path's actual control boundary and full runtime registration.
        // Minecraft/ClientRuntime are deliberately not initialized: attempting takeover fails this test.
        IntentTaskRecord record;
        try {
            record = (IntentTaskRecord) dispatch.invoke(null, design, player,
                (Supplier<IntentTaskRecord>) () -> runtime.execute(player, design, null,
                        "read-only-request", preview -> { published.set(preview); return true; }));
            check(brainField.get(null) == existingBrain && field(slotType, "record").get(existingSlot) == bodyRecord
                            && bodyRecord.getState() == TaskState.RUNNING,
                    "design may not replace or stop the active body task");
        } finally { brainField.set(null, previousBrain); }
        check(record.getState() == TaskState.SUCCESS && record.terminalSnapshot() != null
                        && runtime.task(record.externalId()) == record,
                "the entry path must return a retained terminal task_id without using the scheduler");
        check(runtime.execute(player, design, null, "read-only-request", ignored -> {
            throw new AssertionError("idempotent execution republished the preview");
        }) == record, "request_key must retain the original read-only result");
        var modelParameters = JsonParser.parseString("""
                {"operation":"preview","blueprint":{"blocks":[
                  {"offset":[-2,0,3],"block_id":"minecraft:spruce_planks"}]}}
                """);
        Goal model = new Goal("maicraft:build", "Preview exact authored block", design.target(),
                modelParameters.toString(), "{}", List.of(), List.of());
        check(IntentRuntime.isReadOnlyDesign(model), "model previews must bypass the body scheduler");
        var modelRecord = runtime.execute(player, model, null, "authored-preview", preview -> {
            published.set(preview); return true;
        });
        check(modelRecord.getState() == TaskState.SUCCESS && published.get().designOnly(),
                "build preview finishes without starting construction");
        check(published.get().cells().get(new BlockPos(-2, 64, 3)).is(Blocks.SPRUCE_PLANKS),
                "model preview must not move the origin or replace the selected material");
        level.loaded = false;
        // 图纸涉及未加载格时只返回失败，不能让角色先出去找地或把模型搬到其他位置。
        var unavailable = runtime.execute(player, design, null, "unloaded-preview", ignored -> {
            throw new AssertionError("unloaded model cells must not publish a speculative blueprint");
        });
        check(unavailable.getState() == TaskState.FAILED
                        && JsonParser.parseString(unavailable.terminalSnapshot().resultJson()).getAsJsonObject()
                                .get("message").getAsString().contains("loaded buildable terrain"),
                "unloaded model cells must stop explicitly without site investigation");
        check(player.position().equals(new Vec3(.5, 64, .5)) && player.getInventory().isEmpty(),
                "failed preview must not move the player or acquire materials");
        System.out.println("BuildDesignPreviewTest: authored blueprint previews preserve read-only execution");
    }

    private static Goal goal(String ability) {
        // 固定蓝图原点和材质，预览与施工入口共同使用同一份设计。
        var p = JsonParser.parseString("""
                {"blueprint":{"blocks":[{"offset":[0,0,0],"block_id":"minecraft:oak_planks"}]}}
                """);
        return new Goal(ability, "Preview my house without construction",
                new Goal.SemanticTarget("current_place", null, null, null),
                p.toString(), "{}", List.of(), List.of());
    }

    private static final class FlatLevel extends ClientLevel {
        boolean loaded;
        private FlatLevel() { super(null, null, null, null, 0, 0, null, null, false, 0); }
        @Override public boolean hasChunkAt(BlockPos pos) { return loaded; }
        @Override public boolean isLoaded(BlockPos pos) { return loaded; }
        @Override public int getMinBuildHeight() { return -64; }
        @Override public int getHeight() { return 384; }
        @Override public long getGameTime() { return 100; }
        @Override public int getHeight(Heightmap.Types type, int x, int z) { return 64; }
        @Override public BlockState getBlockState(BlockPos pos) {
            check(loaded, "preview read unloaded terrain");
            return (pos.getY() > 63 ? Blocks.AIR : pos.getY() == 63
                    ? Blocks.GRASS_BLOCK : Blocks.DIRT).defaultBlockState();
        }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public boolean setBlock(BlockPos pos, BlockState state, int flags, int depth) {
            throw new AssertionError("preview tried to mutate the world");
        }
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
