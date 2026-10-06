// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import it.unimi.dsi.fastutil.objects.Object2DoubleArrayMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.flag.FeatureFlags;
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
        // 夹具角色初始站定；施工检查还会读取真实惯性，不能让未执行原版构造器的空速度代替静止。
        player.setDeltaMovement(Vec3.ZERO);
        TargetIndex.dropAll();
        level.entities = new LinkedHashMap<>();
        // 通用交互场景默认白天地面；低光回归单独降低有效照度，不让普通战斗混入补光提醒。
        level.localLight = level.skyLight = 15;
        level.lightAvailable = true;
        level.section = new LevelChunkSection(new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY,
                Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES), null);
        level.chunks = h.allocate(LoadedChunks.class);
        // 让预检和导航的“只读已加载区块”入口读取同一份测试地形，不落入未初始化的原版区块内部字段。
        var chunk = h.allocate(TestChunk.class); chunk.owner = level; level.chunks.chunk = chunk;
        ActorControlTestHarness.field(LevelChunk.class, "sections").set(level.chunks.chunk,
                new LevelChunkSection[]{level.section});
        ActorControlTestHarness.field(Level.class, "dimension").set(level, Level.OVERWORLD);
        ActorControlTestHarness.field(Level.class, "registryAccess").set(level, RegistryAccess.EMPTY);
        ActorControlTestHarness.field(LocalPlayer.class, "clientLevel").set(player, level);
        ActorControlTestHarness.field(Entity.class, "level").set(player, level);
        ActorControlTestHarness.field(Player.class, "inventory").set(player, inventory);
        ActorControlTestHarness.field(Player.class, "abilities").set(player, new Abilities());
        ActorControlTestHarness.field(Player.class, "attributes").set(player,
                new AttributeMap(Player.createAttributes().build()));
        initializeVitals();
        // 默认是已知的空配方表，不能让缺少夹具字段被当成“服务器配方观察失败”；配方场景再替换真实测试配方。
        ActorControlTestHarness.field(ClientPacketListener.class, "recipeManager").set(h.connection, new RecipeManager(RegistryAccess.EMPTY));
        // 默认配方簿未解锁；具体合成回归显式登记已学习配方，才能验证快捷摆料与逐格点击的真实分流。
        ActorControlTestHarness.field(LocalPlayer.class, "recipeBook").set(player, new ClientRecipeBook());
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

    /**
     * 推进若干渲染帧的平滑转头；每帧把上次采样时间前移半个上限，等价于 60fps 下每帧 16.7ms。
     * 没有真实渲染循环的夹具靠它跑完补光转向，而不是把补光改成瞬转来迁就测试。
     */
    public void renderFrames(int frames) throws Exception {
        var lastUpdate = ActorControlTestHarness.field(DefaultBodyControlPort.class, "lastLookUpdateNanos");
        for (int frame = 0; frame < frames; frame++) {
            // 反过来把上次采样时间提前一帧，渲染帧才会照 60fps 的步长推进转向。
            lastUpdate.setLong(h.body, System.nanoTime() - 16_666_667L);
            h.body.renderFrame(player);
        }
    }

    public int itemUses() { return mode.items; }
    public int blockUses() { return mode.blocks; }

    /** 批次场景使用真实菜单端口与可见性等待，槽位交换和同步包效果由夹具明确注入。 */
    void enableInventoryTransactions(boolean synchronizedClicks) throws Exception {
        var menu = new InventoryMenu(inventory, true, player);
        ActorControlTestHarness.field(Player.class, "inventoryMenu").set(player, menu); player.containerMenu = menu;
        h.minecraft.screen = h.inventoryScreen(); h.simulateMenuClose();
        mode.inventoryClicks = true; mode.synchronizedClicks = synchronizedClicks;
    }

    /** 原生合成点击需要真实客户端标志、特性集合和配方表；只在合成场景补齐，避免改动其他世界夹具。 */
    void enableCraftingTransactions() throws Exception {
        enableInventoryTransactions(true); mode.craftingClicks = true;
        ActorControlTestHarness.field(Level.class, "isClientSide").setBoolean(level, true);
        ActorControlTestHarness.field(ClientLevel.class, "connection").set(level, h.connection);
        ActorControlTestHarness.field(ClientPacketListener.class, "enabledFeatures").set(h.connection, FeatureFlags.DEFAULT_FLAGS);
    }

    private void initializeVitals() throws Exception {
        // 原生流体高度与实体类型在真实构造器里初始化；夹具角色绕过构造器创建，
        // 提醒观察读取 isInLava 与 isOnFire（经 fireImmune）前必须补齐这两项。
        ActorControlTestHarness.field(Entity.class, "fluidHeight").set(player, new Object2DoubleArrayMap<>(2));
        ActorControlTestHarness.field(Entity.class, "type").set(player, EntityType.PLAYER);
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
        public int blockLight, skyLight, localLight;
        // 区域照明回放按格注入实际读数，模拟隔墙暗角；默认场景仍沿用统一光照。
        public Map<BlockPos, Integer> blockLightByCell;
        public boolean lightAvailable;
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
        // 使用可控原生光照读数，分别回放夜间天空光、火把光和暂时缺失的照度信息。
        @Override public int getBrightness(LightLayer layer, BlockPos pos) {
            if (!lightAvailable) throw new IllegalStateException("lighting unavailable");
            return layer == LightLayer.BLOCK ? blockLightByCell == null ? blockLight
                    : blockLightByCell.getOrDefault(pos, blockLight) : skyLight;
        }
        @Override public int getMaxLocalRawBrightness(BlockPos pos) { return localLight; }
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
        int items, blocks, attacks, releases, recipePlacements;
        InteractionHand usedHand;
        Consumer<Player> itemUse, itemRelease;
        Runnable beforeBlockUse;
        Consumer<BlockPos> breaking;
        int breakStarts;
        boolean inventoryClicks, synchronizedClicks;
        boolean craftingClicks;
        Runnable afterCraftingClick;
        int menuClicks;
        private UseMode() { super(null, null); }
        // 丢弃侧袋与扑火回放只注入原生破坏后观察到的方块变化，正式执行仍由真实 gameMode 操作世界。
        @Override public boolean startDestroyBlock(BlockPos pos, Direction side) {
            if (breaking == null) return super.startDestroyBlock(pos, side);
            breakStarts++; breaking.accept(pos); return true;
        }
        @Override public boolean continueDestroyBlock(BlockPos pos, Direction side) {
            if (breaking == null) return super.continueDestroyBlock(pos, side);
            breaking.accept(pos); return true;
        }
        @Override public void stopDestroyBlock() { if (breaking == null) super.stopDestroyBlock(); }
        // 配方请求只记次数；具体槽位分包同步由合成测试推进，不能在这里提前生成产物。
        @Override public void handlePlaceRecipe(int containerId, RecipeHolder<?> recipe, boolean shift) { recipePlacements++; }
        @Override public void handleInventoryMouseClick(int containerId, int slot, int button, ClickType type, Player player) {
            // 合成回归调用真实菜单点击搬运材料，结果槽同步由场景单独提供，不能靠夹具提前造出成功回执。
            if (craftingClicks) {
                try { player.containerMenu.clicked(slot, button, type, player); }
                catch (RuntimeException unavailableFixture) { throw new AssertionError("native crafting fixture could not click its menu", unavailableFixture); }
                menuClicks++;
                if (afterCraftingClick != null) afterCraftingClick.run();
                if (synchronizedClicks) player.containerMenu.incrementStateId();
                return;
            }
            if (!inventoryClicks) { super.handleInventoryMouseClick(containerId, slot, button, type, player); return; }
            if (type != ClickType.SWAP || !(button >= 0 && button <= 8 || button == 40)) throw new AssertionError("fixture expects an inventory hand swap");
            var source = player.containerMenu.getSlot(slot); var before = source.getItem();
            source.set(player.getInventory().getItem(button)); player.getInventory().setItem(button, before); menuClicks++;
            if (synchronizedClicks) player.containerMenu.incrementStateId();
        }
        @Override public InteractionResult useItem(Player player, InteractionHand hand) {
            // 记录原生物品入口实际使用哪只手，便于确认副手工具没有被偷偷移到主手。
            usedHand = hand;
            items++; if (itemUse != null) itemUse.accept(player); return InteractionResult.PASS;
        }
        @Override public void releaseUsingItem(Player player) {
            releases++; if (itemRelease != null) itemRelease.accept(player); player.stopUsingItem();
        }
        @Override public void attack(Player player, Entity target) { attacks++; }
        @Override public InteractionResult useItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult hit) {
            // 补光须真正调用副手放置，测试不能只凭背包位置推断交互用了哪只手。
            usedHand = hand;
            if (beforeBlockUse != null) beforeBlockUse.run();
            ((TestLevel) player.level()).blockSequence++;
            blocks++; return InteractionResult.PASS;
        }
    }
}
