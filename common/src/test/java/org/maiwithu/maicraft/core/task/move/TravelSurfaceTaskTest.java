// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.move;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.ClientActorBoundary;
import org.maiwithu.maicraft.client.actor.DefaultBodyControlPort;
import org.maiwithu.maicraft.client.actor.DefaultLocalPlayerContext;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.pathing.transport.TransportMode;
import org.maiwithu.maicraft.core.pathing.transport.TransportRuntime;
import org.maiwithu.maicraft.task.TaskState;
import sun.misc.Unsafe;

/**
 * 用惰性身体与可编程柱状世界验证地表任务的露天判定：露天立即到达并携带证据，
 * 头顶盖层、水底与未加载区块都不得误报到达；范围型失败必须携带搜索范围声明。
 */
public final class TravelSurfaceTaskTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Unsafe memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        recordValidation();
        openSkyArrivesImmediately(memory, TransportMode.GROUND);
        openSkyArrivesImmediately(memory, TransportMode.JETPACK);
        coveredCeilingFailsHonestly(memory);
        seabedIsNotSurface(memory);
        unloadedChunkDoesNotArrive(memory);
        System.out.println("TravelSurfaceTaskTest: passed");
    }

    /** 站在露天列时任何交通方式都应立即成功，回执携带“列地表高度=脚底高度”的证据。 */
    private static void openSkyArrivesImmediately(Unsafe memory, TransportMode mode) throws Exception {
        try (var f = new Fixture(memory)) {
            f.world.shape = WorldShape.OPEN;
            var record = new TravelSurfaceTaskRecord("mcp-open", 1000, 64, mode, false);
            var task = new TravelSurfaceTask(f.player, record);
            task.start(f.player);
            check(task.onTick() == TaskState.SUCCESS,
                    mode + ": standing on an open-sky column must arrive without launching any transport");
            check(record.internalVerifiedPosition() != null, "an open-sky arrival must issue the verified position");
            var result = task.result(TaskState.SUCCESS);
            check(result.success() && result.message().contains("open sky"),
                    "the success receipt must name the open-sky verdict");
            var evidence = (Map<?, ?>) result.data().get("open_sky_evidence");
            check(evidence != null
                            && ((Number) evidence.get("feet_y")).intValue() == 0
                            && ((Number) evidence.get("column_surface_y")).intValue() == 0,
                    "the receipt must carry matching feet and column-surface heights as evidence");
        }
    }

    /** 头顶有实心盖层时不得到达：地面模式没有候选应按范围失败收场，而不是把坑底报成地表。 */
    private static void coveredCeilingFailsHonestly(Unsafe memory) throws Exception {
        try (var f = new Fixture(memory)) {
            f.world.shape = WorldShape.SOLID_ROCK;
            var record = new TravelSurfaceTaskRecord("mcp-covered", 200000, 64, TransportMode.GROUND, false);
            var task = new TravelSurfaceTask(f.player, record);
            task.start(f.player);
            TaskState last = TaskState.RUNNING;
            int ticks = 0;
            while (last == TaskState.RUNNING && ticks++ < 400) {
                f.nextTick();
                last = task.onTick();
            }
            check(last == TaskState.FAILED, "a fully covered column must end in an honest failure");
            check(record.internalVerifiedPosition() == null, "a covered column must not issue an arrival receipt");
            var result = task.result(TaskState.FAILED);
            check(!result.success() && result.message().contains("within 64 blocks")
                            && result.message().contains("remains unknown"),
                    "the range failure must declare the searched radius and that unseen terrain stays unknown");
            check(String.valueOf(result.data().get("failure_type")).equalsIgnoreCase("no_path"),
                    "no reachable open-sky column is a path failure, not an internal error");
        }
    }

    /** 水体按阻挡计：站在海底不算露天。 */
    private static void seabedIsNotSurface(Unsafe memory) throws Exception {
        try (var f = new Fixture(memory)) {
            f.world.shape = WorldShape.WATER_ABOVE;
            var record = new TravelSurfaceTaskRecord("mcp-seabed", 200000, 64, TransportMode.GROUND, false);
            var task = new TravelSurfaceTask(f.player, record);
            task.start(f.player);
            TaskState last = TaskState.RUNNING;
            int ticks = 0;
            while (last == TaskState.RUNNING && ticks++ < 400) {
                f.nextTick();
                last = task.onTick();
            }
            check(last == TaskState.FAILED && record.internalVerifiedPosition() == null,
                    "standing on a seabed must not be reported as open sky");
        }
    }

    /** 未加载的列既不判露天也不判封闭：不得误报到达，失败说明保留未知语义。 */
    private static void unloadedChunkDoesNotArrive(Unsafe memory) throws Exception {
        try (var f = new Fixture(memory)) {
            f.world.shape = WorldShape.OPEN;
            f.world.chunksLoaded = false;
            var record = new TravelSurfaceTaskRecord("mcp-unloaded", 200000, 64, TransportMode.GROUND, false);
            var task = new TravelSurfaceTask(f.player, record);
            task.start(f.player);
            TaskState last = TaskState.RUNNING;
            int ticks = 0;
            while (last == TaskState.RUNNING && ticks++ < 400) {
                f.nextTick();
                last = task.onTick();
            }
            check(last == TaskState.FAILED && record.internalVerifiedPosition() == null,
                    "an unloaded standing column must neither arrive nor invent a verdict");
            check(task.result(TaskState.FAILED).message().contains("remains unknown"),
                    "unloaded terrain must stay unknown in the receipt wording");
        }
    }

    private static void recordValidation() {
        for (int bad : new int[]{7, 129}) {
            try {
                new TravelSurfaceTaskRecord("mcp-bound", 1000, bad, TransportMode.GROUND, false);
                throw new AssertionError("radius " + bad + " must be rejected");
            } catch (IllegalArgumentException expected) { }
        }
        try {
            new TravelSurfaceTaskRecord("mcp-elevator", 1000, 64, TransportMode.ELEVATOR, false);
            throw new AssertionError("elevator cannot serve open-sky discovery");
        } catch (IllegalArgumentException expected) { }
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private enum WorldShape {
        /** 石头在 y=-1 以下，露天。 */
        OPEN,
        /** 整列实心岩石，heightmap 顶抬到 21，模拟坑道被彻底封死的覆盖层。 */
        SOLID_ROCK,
        /** 石头之上有五格水，模拟站在海底。 */
        WATER_ABOVE
    }

    private static final class Fixture implements AutoCloseable {
        final ClientActorBoundary actor = ClientRuntime.actor();
        final Map<Field, Object> saved = new LinkedHashMap<>();
        final Minecraft minecraft;
        final SurfacePlayer player;
        final SurfaceWorld world;
        final DefaultBodyControlPort body = new DefaultBodyControlPort();

        Fixture(Unsafe memory) throws Exception {
            minecraft = (Minecraft) memory.allocateInstance(Minecraft.class);
            player = (SurfacePlayer) memory.allocateInstance(SurfacePlayer.class);
            // 惰性玩家的身体尺寸未初始化；地形采样会读取它来放身体盒。
            field(Entity.class, "dimensions").set(player, EntityDimensions.scalable(.6F, 1.8F));
            var inventoryMenu = (InventoryMenu) memory.allocateInstance(InventoryMenu.class);
            inventoryMenu.setCarried(ItemStack.EMPTY);
            field(LocalPlayer.class, "inventoryMenu").set(player, inventoryMenu); player.containerMenu = inventoryMenu;
            world = (SurfaceWorld) memory.allocateInstance(SurfaceWorld.class);
            // Objenesis 不跑字段初始化器；区块加载默认真，由用例自行翻假模拟未加载。
            world.chunksLoaded = true;
            minecraft.player = player; minecraft.level = world;
            field(Minecraft.class, "gameThread").set(minecraft, Thread.currentThread());
            field(Level.class, "dimension").set(world, Level.OVERWORLD);
            field(LocalPlayer.class, "clientLevel").set(player, world);
            field(LocalPlayer.class, "level").set(player, world);
            field(LocalPlayer.class, "position").set(player, new Vec3(.5, 0, .5));
            field(LocalPlayer.class, "blockPosition").set(player, BlockPos.containing(.5, 0, .5));
            field(LocalPlayer.class, "onGround").setBoolean(player, true);
            player.input = new Input();
            replace(field(Minecraft.class, "instance"), null, minecraft);
            replace(field(ClientActorBoundary.class, "minecraft"), actor, minecraft);
            replace(field(ClientActorBoundary.class, "body"), actor, body);
            replace(field(ClientActorBoundary.class, "observedPlayer"), actor, player);
            saved.put(field(ClientActorBoundary.class, "activeContext"), field(ClientActorBoundary.class, "activeContext").get(actor));
            saved.put(field(ClientActorBoundary.class, "tickRevision"), field(ClientActorBoundary.class, "tickRevision").get(actor));
            var fulfill = DefaultBodyControlPort.class.getDeclaredMethod("requestAutomation", LocalPlayer.class);
            fulfill.setAccessible(true); fulfill.invoke(body, player);
            var grant = DefaultBodyControlPort.class.getDeclaredMethod("fulfillAutomationRequest", LocalPlayer.class);
            grant.setAccessible(true); grant.invoke(body, player);
            nextTick();
        }

        void nextTick() throws Exception {
            world.time++;
            field(ClientActorBoundary.class, "tickRevision").setLong(actor, world.time);
            var ctor = DefaultLocalPlayerContext.class.getDeclaredConstructors()[0]; ctor.setAccessible(true);
            LocalPlayerContext context = (LocalPlayerContext) ctor.newInstance(actor, minecraft, player, world, null, null,
                    field(ClientActorBoundary.class, "bodyEpoch").getLong(actor),
                    field(ClientActorBoundary.class, "controlRevision").getLong(actor), world.time, true);
            field(ClientActorBoundary.class, "activeContext").set(actor, context);
            var begin = DefaultBodyControlPort.class.getDeclaredMethod("beginTick", long.class);
            begin.setAccessible(true); begin.invoke(body, world.time);
        }

        private void replace(Field target, Object owner, Object value) throws Exception {
            saved.put(target, target.get(owner)); target.set(owner, value);
        }

        public void close() throws Exception {
            TransportRuntime.abandon();
            for (var entry : saved.entrySet()) entry.getKey().set(
                    entry.getKey().getDeclaringClass() == Minecraft.class ? null : actor, entry.getValue());
        }
    }

    /** 柱状世界的形态与 {@code ClientSurfaceHeight} 的读取方式一致：heightmap 顶永远指向最高遮挡物上一格。 */
    private static final class SurfaceWorld extends ClientLevel {
        long time;
        boolean chunksLoaded = true;
        WorldShape shape = WorldShape.OPEN;
        private SurfaceWorld() { super(null, null, null, null, 0, 0, null, null, false, 0); }
        @Override public long getGameTime() { return time; }
        @Override public boolean hasChunkAt(BlockPos pos) { return chunksLoaded; }
        // 基类从空的 dimensionType 读世界上下界都会炸；柱状世界固定为原版主世界边界。
        @Override public int getMinBuildHeight() { return -64; }
        @Override public int getMaxBuildHeight() { return 320; }
        @Override public int getHeight(Heightmap.Types type, int x, int z) {
            return switch (shape) {
                case OPEN -> 0;
                case SOLID_ROCK -> 21;
                case WATER_ABOVE -> 5;
            };
        }
        @Override public BlockState getBlockState(BlockPos pos) {
            return switch (shape) {
                case OPEN -> pos.getY() < 0 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
                case SOLID_ROCK -> Blocks.STONE.defaultBlockState();
                case WATER_ABOVE -> pos.getY() < 0 ? Blocks.STONE.defaultBlockState()
                        : pos.getY() <= 4 ? Blocks.WATER.defaultBlockState()
                        : Blocks.AIR.defaultBlockState();
            };
        }
        // 基类的流体读取会经过空 chunkSource；直接从桩方块状态推导，与 ClientSurfaceHeightTest 的柱状世界一致。
        @Override public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }
    }

    private static final class SurfacePlayer extends LocalPlayer {
        private SurfacePlayer() { super(null, null, null, null, null, false, false); }
        // 惰性玩家没有背包；地形采样的碰撞上下文会读主手物品，这里按夹具惯例返回空。
        @Override public ItemStack getItemBySlot(net.minecraft.world.entity.EquipmentSlot slot) { return ItemStack.EMPTY; }
        @Override public boolean isAlive() { return true; }
        @Override public boolean isSleeping() { return false; }
        @Override public float getHealth() { return 20; }
        @Override public float getAbsorptionAmount() { return 0; }
        @Override public void setSprinting(boolean sprinting) { }
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try { Field found = type.getDeclaredField(name); found.setAccessible(true); return found; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
}
