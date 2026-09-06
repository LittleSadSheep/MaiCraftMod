package org.maiwithu.maicraft.core.pathing.baritone.landing;

import it.unimi.dsi.fastutil.longs.LongSets;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuPort;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.actor.NativeActionPort;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import sun.misc.Unsafe;

/** Vanilla mechanisms on inert fixtures plus loaded landing geometry and narrowly scoped ownership. */
public final class LandingAssistTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Map<Object, Object> priorTags = new HashMap<>();
        Field tags = field(Holder.Reference.class, "tags");
        try {
            for (Block block : new Block[]{Blocks.SWEET_BERRY_BUSH, Blocks.COBWEB, Blocks.TWISTING_VINES,
                    Blocks.TWISTING_VINES_PLANT, Blocks.WEEPING_VINES, Blocks.WEEPING_VINES_PLANT}) {
                Object holder = block.builtInRegistryHolder();
                priorTags.put(holder, tags.get(holder));
                tags.set(holder, Set.of(BlockTags.FALL_DAMAGE_RESETTING));
            }
            plans(); geometry(); resettingRay(); nativeMechanisms(); interruptedPreparation();
        } finally { for (var entry : priorTags.entrySet()) tags.set(entry.getKey(), entry.getValue()); }
        System.out.println("LandingAssistTest: passed");
    }

    private static void plans() {
        check(LandingAssistSession.healthDecreased(18, 4, 20, 2), "healing must not hide absorption damage");
        check(LandingAssistSession.healthDecreased(20, 2, 19, 4), "new absorption must not hide HP damage");
        check(!LandingAssistSession.healthDecreased(19, 2, 20, 3), "gains alone are not a damage event");
        Scene scene = new Scene();
        var all = new LandingAssistPlan.InventorySnapshot(Set.of(LandingAssistPlan.Kind.values()), true, true, false);
        var waterOnly = new LandingAssistPlan.InventorySnapshot(Set.of(LandingAssistPlan.Kind.values()), true, false, false);
        check(waterOnly.plans(scene, BlockPos.ZERO, pos -> false).stream().allMatch(p -> p.kind() == LandingAssistPlan.Kind.WATER),
                "legacy water consent never authorizes block aids");
        check(TerrainPermit.LANDING_ONLY.mayUseLandingAssists() && TerrainPermit.LANDING_ONLY.mayUseWaterBucket()
                        && !TerrainPermit.LANDING_ONLY.mayAlter() && !TerrainPermit.WATER_ONLY.mayUseLandingAssists(),
                "landing-only permission cannot become excavation or scaffolding authority");
        check(all.plans(scene, BlockPos.ZERO, pos -> true).isEmpty(), "protected placement cannot consume a clutch item");
        check(!LandingAssistPlan.canPlace(LandingAssistPlan.Kind.BERRIES, scene, BlockPos.ZERO), "berries need native soil");
        scene.blocks.put(BlockPos.ZERO.below(), Blocks.FARMLAND.defaultBlockState());
        check(LandingAssistPlan.canPlace(LandingAssistPlan.Kind.BERRIES, scene, BlockPos.ZERO), "farmland is valid berry soil");
        scene.blocks.clear();
        check(LandingAssistPlan.canPlace(LandingAssistPlan.Kind.TWISTING_VINES, scene, BlockPos.ZERO), "twisting vines attach upward from sturdy ground");
        check(!LandingAssistPlan.canPlace(LandingAssistPlan.Kind.WEEPING_VINES, scene, BlockPos.ZERO), "weeping vines cannot attach to ground");
        scene.blocks.put(BlockPos.ZERO.above(), Blocks.WEEPING_VINES_PLANT.defaultBlockState());
        check(LandingAssistPlan.canPlace(LandingAssistPlan.Kind.WEEPING_VINES, scene, BlockPos.ZERO), "weeping vine extends a valid hanging anchor");
        scene.blocks.clear();
        scene.blocks.put(BlockPos.ZERO.below(), Blocks.STONE_STAIRS.defaultBlockState());
        check(!LandingAssistPlan.canPlace(LandingAssistPlan.Kind.WATER, scene, BlockPos.ZERO), "waterlogging cannot masquerade as water in the landing cell");
        scene.blocks.clear();
        scene.blocks.put(BlockPos.ZERO, Blocks.SHORT_GRASS.defaultBlockState());
        check(all.plans(scene, BlockPos.ZERO, pos -> false).isEmpty(), "no aid replaces an existing plant or block");
        var young = Blocks.SWEET_BERRY_BUSH.defaultBlockState();
        var grown = young.setValue(BlockStateProperties.AGE_3, 1);
        check(LandingAssistPlan.existingSafe(LandingAssistPlan.Kind.BERRIES, young)
                        && !LandingAssistPlan.existingSafe(LandingAssistPlan.Kind.BERRIES, grown), "grown berry horizontal contact is not damage-free");
        check(!LandingAssistPlan.canRecover(false, young, young, false)
                        && !LandingAssistPlan.canRecover(true, young, grown, false)
                        && !LandingAssistPlan.canRecover(true, young, young, true)
                        && LandingAssistPlan.canRecover(true, young, young, false), "only unchanged, unprotected, confirmed own placement may be removed");
    }

    private static void geometry() {
        Scene scene = new Scene();
        var plan = new LandingAssistPlan(LandingAssistPlan.Kind.SLIME, BlockPos.ZERO, BlockPos.ZERO,
                BlockPos.ZERO.below(), net.minecraft.core.Direction.UP, false);
        check(LandingAssistGeometry.safe(scene, pos -> true, plan, 0.6, 1.8, LongSets.emptySet()), "new slime validates the raised support height");
        scene.blocks.put(BlockPos.ZERO.above(2), Blocks.STONE.defaultBlockState());
        check(!LandingAssistGeometry.safe(scene, pos -> true, plan, 0.6, 1.8, LongSets.emptySet()), "raising slime into a low ceiling is unsafe");
        scene.blocks.clear();
        check(!LandingAssistGeometry.safe(scene, pos -> pos.getX() != 1, plan, 0.6, 1.8, LongSets.emptySet()), "unloaded neighboring shape ownership blocks proof");
        check(!LandingAssistGeometry.safe(scene, pos -> true, plan, 0.6, 1.8, LongSets.singleton(BlockPos.ZERO.above(2).asLong())), "full body obeys forbidden head cells");
        scene.blocks.put(BlockPos.ZERO.below(), Blocks.AIR.defaultBlockState());
        check(!LandingAssistGeometry.safeAfterRemoval(scene, pos -> true, plan, 0.6, 1.8, LongSets.emptySet()),
                "own slime must remain if removing it would leave an unsupported fall");
    }

    private static void resettingRay() {
        Scene scene = new Scene();
        for (Block block : new Block[]{Blocks.SWEET_BERRY_BUSH, Blocks.COBWEB, Blocks.TWISTING_VINES, Blocks.WEEPING_VINES}) {
            scene.blocks.put(BlockPos.ZERO, block.defaultBlockState());
            var hit = scene.clip(new ClipContext(new Vec3(0.1, 12, 0.1), new Vec3(0.1, 0, 0.1),
                    ClipContext.Block.FALLDAMAGE_RESETTING, ClipContext.Fluid.WATER, CollisionContext.empty()));
            check(hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(BlockPos.ZERO),
                    "native high-speed fall ray catches the full resetting cell even outside the vine outline");
        }
    }

    private static void nativeMechanisms() throws Exception {
        Unsafe memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        DummyPlayer player = (DummyPlayer) memory.allocateInstance(DummyPlayer.class);
        ClientLevel level = (ClientLevel) memory.allocateInstance(ClientLevel.class);
        field(Level.class, "isClientSide").setBoolean(level, true);
        field(Level.class, "damageSources").set(level, memory.allocateInstance(DamageSources.class));
        field(Player.class, "abilities").set(player, new Abilities());
        field(Entity.class, "level").set(player, level);
        field(Entity.class, "type").set(player, EntityType.PLAYER);
        player.fallDistance = 40;
        Blocks.COBWEB.defaultBlockState().entityInside(level, BlockPos.ZERO, player);
        check(player.fallDistance == 0, "native cobweb makeStuckInBlock resets fall distance");
        player.fallDistance = 40;
        Blocks.SWEET_BERRY_BUSH.defaultBlockState().entityInside(level, BlockPos.ZERO, player);
        check(player.fallDistance == 0, "native young berry contact resets fall distance");
        var climb = LivingEntity.class.getDeclaredMethod("handleOnClimbable", Vec3.class); climb.setAccessible(true);
        player.fallDistance = 40;
        Vec3 slowed = (Vec3) climb.invoke(player, new Vec3(0, -3, 0));
        check(player.fallDistance == 0 && slowed.y > -0.151, "native climbable handling resets and clamps descent");
        Blocks.SLIME_BLOCK.fallOn(level, Blocks.SLIME_BLOCK.defaultBlockState(), BlockPos.ZERO, player, 40);
        check(player.lastMultiplier == 0, "non-sneaking native slime suppresses fall damage");
        player.sneak = true;
        Blocks.SLIME_BLOCK.fallOn(level, Blocks.SLIME_BLOCK.defaultBlockState(), BlockPos.ZERO, player, 40);
        check(player.lastMultiplier == 1, "sneaking restores ordinary slime fall damage");
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void interruptedPreparation() throws Exception {
        Unsafe memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        LocalPlayer player = (LocalPlayer) memory.allocateInstance(LocalPlayer.class); // onGround=false
        int[] closes = {0}, retired = {0};
        MenuReceipt[] closing = {null};
        var menuCtor = MenuReceipt.class.getDeclaredConstructors()[0]; menuCtor.setAccessible(true);
        var nativeCtor = NativeActionReceipt.class.getDeclaredConstructors()[0]; nativeCtor.setAccessible(true);
        var finish = NativeActionReceipt.class.getDeclaredMethod("finish", NativeActionReceipt.Status.class, String.class); finish.setAccessible(true);
        MenuPort menus = (MenuPort) Proxy.newProxyInstance(MenuPort.class.getClassLoader(), new Class<?>[]{MenuPort.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "closeForTaskBoundary" -> {
                        closes[0]++;
                        closing[0] = (MenuReceipt) menuCtor.newInstance(MenuReceipt.Kind.CLOSE, args[0], 0, 0, 20, true, null);
                        yield closing[0];
                    }
                    case "poll" -> args[1];
                    default -> throw new AssertionError("airborne preparation must not open or mutate an inventory: " + method.getName());
                });
        NativeActionPort actions = (NativeActionPort) Proxy.newProxyInstance(NativeActionPort.class.getClassLoader(), new Class<?>[]{NativeActionPort.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "poll" -> args[1];
                    case "retireOneShotForTaskBoundary" -> {
                        retired[0]++; finish.invoke(args[1], NativeActionReceipt.Status.UNCERTAIN, "preparation interrupted"); yield args[1];
                    }
                    default -> throw new AssertionError("airborne preparation must not select or use items: " + method.getName());
                });
        LocalPlayerContext context = (LocalPlayerContext) Proxy.newProxyInstance(LocalPlayerContext.class.getClassLoader(), new Class<?>[]{LocalPlayerContext.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "player" -> player;
                    case "menus" -> menus;
                    case "actions" -> actions;
                    case "permitsNativeActions", "isCurrent", "mutationAvailable" -> true;
                    case "bodyEpoch", "controlRevision", "tickRevision" -> 1L;
                    default -> throw new AssertionError("unexpected preparation context access: " + method.getName());
                });
        var preparation = new LandingPreparation(net.minecraft.world.item.Items.WATER_BUCKET);
        field(LandingPreparation.class, "inventoryTouched").setBoolean(preparation, true);
        field(LandingPreparation.class, "selection").set(preparation, nativeCtor.newInstance(NativeActionReceipt.Kind.SELECT_HOTBAR,
                context, 20, 2, NativeConfirmation.pending(), null, null));
        check(!preparation.tick(context) && !preparation.failed() && closes[0] == 1 && retired[0] == 1,
                "leaving ground settles pending selection and starts only the owned GUI close");
        var closeFinish = MenuReceipt.class.getDeclaredMethod("finish", MenuReceipt.Status.class, String.class); closeFinish.setAccessible(true);
        closeFinish.invoke(closing[0], MenuReceipt.Status.CONFIRMED_APPLIED, "closed");
        check(!preparation.tick(context) && preparation.failed() && closes[0] == 1,
                "interrupted preparation reports failure after confirming closure, without duplicate operations");
        check(!preparation.acceptHeld(context), "acceptHeld cannot bypass a preparation's outstanding GUI ownership");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static final class DummyPlayer extends LocalPlayer {
        boolean sneak; float lastMultiplier;
        private DummyPlayer() { super(null, null, null, null, null, false, false); }
        public boolean isShiftKeyDown() { return sneak; }
        public boolean onClimbable() { return true; }
        public BlockState getInBlockState() { return Blocks.TWISTING_VINES.defaultBlockState(); }
        public boolean hasEffect(Holder<MobEffect> effect) { return false; }
        public boolean causeFallDamage(float distance, float multiplier, DamageSource source) { lastMultiplier = multiplier; return false; }
    }
    private static final class Scene implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        public BlockState getBlockState(BlockPos pos) { return blocks.getOrDefault(pos, (pos.getY() == -1 ? Blocks.STONE : Blocks.AIR).defaultBlockState()); }
        public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        public int getHeight() { return 384; }
        public int getMinBuildHeight() { return -64; }
    }
}
