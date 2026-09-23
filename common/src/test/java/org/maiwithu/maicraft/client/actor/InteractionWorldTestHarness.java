// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.scan.TargetIndex;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 在一个已加载区块内提供可修改的方块、背包和原生操作计数，供交互测试复用；越界读取会报错，关闭时恢复之前的全局客户端与控制状态。
 */
public final class InteractionWorldTestHarness implements AutoCloseable {
    final ActorControlTestHarness h = new ActorControlTestHarness();
    public final LocalPlayer player = h.player;
    public final TestLevel level = h.allocate(TestLevel.class);
    public final Inventory inventory = new Inventory(player);
    final UseMode mode = h.allocate(UseMode.class);
    final ClientActorBoundary actor = ClientRuntime.actor();
    final Map<Field, Object> saved = new LinkedHashMap<>();
    final Field global = ActorControlTestHarness.field(Minecraft.class, "instance");
    final Object previous = global.get(null);

    public InteractionWorldTestHarness() throws Exception {
        TargetIndex.dropAll();
        level.entities = new LinkedHashMap<>();
        level.section = new LevelChunkSection(new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY,
                Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES), null);
        level.chunks = h.allocate(LoadedChunks.class);
        // 让预检和导航的“只读已加载区块”入口读取同一份测试地形，不落入未初始化的原版区块内部字段。
        var chunk = h.allocate(TestChunk.class); chunk.owner = level; level.chunks.chunk = chunk;
        ActorControlTestHarness.field(LevelChunk.class, "sections").set(level.chunks.chunk,
                new LevelChunkSection[]{level.section});
        ActorControlTestHarness.field(Level.class, "dimension").set(level, Level.OVERWORLD);
        ActorControlTestHarness.field(LocalPlayer.class, "clientLevel").set(player, level);
        ActorControlTestHarness.field(Entity.class, "level").set(player, level);
        ActorControlTestHarness.field(Player.class, "inventory").set(player, inventory);
        ActorControlTestHarness.field(Player.class, "abilities").set(player, new Abilities());
        ActorControlTestHarness.field(Player.class, "attributes").set(player,
                new AttributeMap(Player.createAttributes().build()));
        initializeVitals();
        ActorControlTestHarness.field(Entity.class, "eyeHeight").setFloat(player, 1.62F);
        ActorControlTestHarness.field(Entity.class, "onGround").setBoolean(player, true);
        position(new Vec3(.5, 1, 3.5));
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
            set(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
        h.minecraft.level = level; h.minecraft.gameMode = mode;
        global.set(null, h.minecraft);
        for (Field field : ClientActorBoundary.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true); saved.put(field, field.get(actor)); field.set(actor, field.get(h.actor));
        }
        nextTick();
    }

    public void position(Vec3 at) throws Exception {
        ActorControlTestHarness.field(Entity.class, "position").set(player, at);
        ActorControlTestHarness.field(Entity.class, "blockPosition").set(player, BlockPos.containing(at));
        ActorControlTestHarness.field(Entity.class, "bb").set(player,
                new AABB(at.x - .3, at.y, at.z - .3, at.x + .3, at.y + 1.8, at.z + .3));
    }

    // 修改测试区块后也发送目标索引变更通知，让依赖索引的检查看到同一次变化。
    public void set(BlockPos pos, BlockState state) {
        BlockState before = level.getBlockState(pos);
        level.section.setBlockState(pos.getX(), pos.getY(), pos.getZ(), state);
        TargetIndex.onBlockChange(level, pos, before, state);
    }

    public void nextTick() throws Exception {
        h.nextTick(true); level.time++;
        ActorControlTestHarness.field(ClientActorBoundary.class, "tickRevision").setLong(actor, h.tick);
        h.context = new DefaultLocalPlayerContext(actor, h.minecraft, player, level, mode, h.connection, 0, 0, h.tick, true);
        ActorControlTestHarness.field(ClientActorBoundary.class, "activeContext").set(actor, h.context);
    }

    public int itemUses() { return mode.items; }
    public int blockUses() { return mode.blocks; }

    private void initializeVitals() throws Exception {
        // 正常施工会持续观察生命和饥饿；夹具默认健康吃饱，具体饥饿测试再显式改成低值，不能依赖未初始化字段。
        ActorControlTestHarness.field(Player.class, "foodData").set(player, new FoodData());
        var builder = new SynchedEntityData.Builder(player);
        define(builder, "DATA_SHARED_FLAGS_ID", (byte) 0); define(builder, "DATA_AIR_SUPPLY_ID", 300);
        define(builder, "DATA_CUSTOM_NAME_VISIBLE", false); define(builder, "DATA_CUSTOM_NAME", Optional.empty());
        define(builder, "DATA_SILENT", false); define(builder, "DATA_NO_GRAVITY", false);
        define(builder, "DATA_POSE", Pose.STANDING); define(builder, "DATA_TICKS_FROZEN", 0);
        var method = Player.class.getDeclaredMethod("defineSynchedData", SynchedEntityData.Builder.class);
        method.setAccessible(true); method.invoke(player, builder);
        ActorControlTestHarness.field(Entity.class, "entityData").set(player, builder.build()); player.setHealth(20);
    }
    @SuppressWarnings("unchecked") private static <T> void define(SynchedEntityData.Builder builder, String name, T value) throws Exception {
        builder.define((EntityDataAccessor<T>) ActorControlTestHarness.field(Entity.class, name).get(null), value);
    }

    // 先释放测试按键和索引，再逐项还原原来的全局对象，避免影响后面测试。
    public void close() throws Exception {
        h.body.releaseAll(); TargetIndex.dropAll();
        for (var entry : saved.entrySet()) entry.getKey().set(actor, entry.getValue());
        global.set(null, previous);
    }

    public static final class TestLevel extends ClientLevel implements BlockUseAcknowledgement {
        public Map<Integer, Entity> entities;
        public int blockSequence, acknowledgedSequence;
        @Override public int maicraft$currentBlockSequence() { return blockSequence; }
        @Override public int maicraft$acknowledgedBlockSequence() { return acknowledgedSequence; }
        LevelChunkSection section;
        LoadedChunks chunks;
        public int blockReads, searches;
        public boolean clientHeightmapsOnly;
        long time;
        private TestLevel() { super(null, null, null, null, 0, 0, null, null, false, 0); }
        @Override public boolean isLoaded(BlockPos pos) { return pos.getX() >> 4 == 0 && pos.getZ() >> 4 == 0; }
        @Override public BlockState getBlockState(BlockPos pos) {
            blockReads++;
            if (!isLoaded(pos)) throw new AssertionError("read from an unloaded cell: " + pos);
            return pos.getY() < 0 || pos.getY() >= 16 ? Blocks.AIR.defaultBlockState()
                    : section.getBlockState(pos.getX() & 15, pos.getY(), pos.getZ() & 15);
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public ClientChunkCache getChunkSource() { searches++; return chunks; }
        @Override public BlockGetter getChunkForCollisions(int x, int z) {
            return x == 0 && z == 0 ? this : null;
        }
        @Override public int getHeight() { return 16; }
        @Override public int getMinBuildHeight() { return 0; }
        @Override public int getHeight(Heightmap.Types type, int x, int z) {
            // 模拟真实客户端：服务端不会发来的高度图没有地面数据，不能误用它判断出坑位置。
            if (clientHeightmapsOnly && !type.sendToClient()) return getMinBuildHeight();
            for (int y = 15; y >= 0; y--) if (!getBlockState(new BlockPos(x, y, z)).isAir()) return y + 1;
            return 0;
        }
        private WorldBorder testBorder;
        @Override public WorldBorder getWorldBorder() {
            if (testBorder == null) testBorder = new WorldBorder();
            return testBorder;
        }
        @Override public long getGameTime() { return time; }
        @Override public Entity getEntity(int id) { return entities.get(id); }
        @Override public Iterable<Entity> entitiesForRendering() { return List.copyOf(entities.values()); }
        @Override public List<Entity> getEntities(Entity entity, AABB bounds, Predicate<? super Entity> filter) {
            return entities.values().stream().filter(e -> e != entity && e.getBoundingBox().intersects(bounds) && filter.test(e)).toList();
        }
        @Override public <T extends Entity> List<T> getEntities(
                EntityTypeTest<Entity, T> type, AABB bounds, Predicate<? super T> filter) {
            return entities.values().stream().map(type::tryCast).filter(Objects::nonNull)
                    .filter(e -> e.getBoundingBox().intersects(bounds) && filter.test(e)).toList();
        }
    }

    private static final class TestChunk extends LevelChunk {
        private TestLevel owner;
        private TestChunk() { super(null, new ChunkPos(0, 0)); }
        @Override public BlockState getBlockState(BlockPos pos) { return owner.getBlockState(pos); }
        @Override public FluidState getFluidState(BlockPos pos) { return owner.getFluidState(pos); }
        @Override public ChunkPos getPos() { return new ChunkPos(0, 0); }
    }

    private static final class LoadedChunks extends ClientChunkCache {
        LevelChunk chunk;
        private LoadedChunks() { super(null, 0); }
        @Override public LevelChunk getChunk(int x, int z, ChunkStatus status, boolean load) {
            // 原版 getBlockEntity 会以 load=true 查询已驻留区块；此操作不会创建新区块。
            if (x == 0 && z == 0) return chunk;
            if (load) throw new AssertionError("interaction attempted to load a chunk");
            return null;
        }
    }

    static final class UseMode extends MultiPlayerGameMode {
        int items, blocks, attacks, releases;
        Consumer<Player> itemUse, itemRelease;
        Runnable beforeBlockUse;
        private UseMode() { super(null, null); }
        @Override public InteractionResult useItem(Player player, InteractionHand hand) {
            items++; if (itemUse != null) itemUse.accept(player); return InteractionResult.PASS;
        }
        @Override public void releaseUsingItem(Player player) {
            releases++; if (itemRelease != null) itemRelease.accept(player); player.stopUsingItem();
        }
        @Override public void attack(Player player, Entity target) { attacks++; }
        @Override public InteractionResult useItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult hit) {
            if (beforeBlockUse != null) beforeBlockUse.run();
            ((TestLevel) player.level()).blockSequence++;
            blocks++; return InteractionResult.PASS;
        }
    }
}
