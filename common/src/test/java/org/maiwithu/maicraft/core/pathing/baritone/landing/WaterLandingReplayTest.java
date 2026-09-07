package org.maiwithu.maicraft.core.pathing.baritone.landing;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.DefaultNativeActionPort;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeActionPort;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import sun.misc.Unsafe;

/** Real session/receipt/voxel rays on inert fixtures; dry gravity is replayed between actor ticks. */
public final class WaterLandingReplayTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        for (int drop : new int[]{4, 12, 20}) replay(drop);
        missingEvidence(); existingWater(); postRemovalSupport(); stalePreparation(); window();
        System.out.println("WaterLandingReplayTest: passed");
    }

    private static void replay(int drop) throws Exception {
        Fixture f = new Fixture(false);
        f.position(drop, 0, true);
        check(f.session.prepareAlreadyHeld(f.context) && f.session.prepare(f.context), "bucket ready before departure");
        check(f.uses == 0, "preparing on support cannot pour water");
        double height = drop, velocity = 0;
        while (height > 0) {
            f.position(height, velocity, false);
            f.player.setXRot(90); // Precision camera has settled while descending toward the face.
            f.tick();
            check(!f.session.complete(), "fall coordinates cannot complete the active landing session");
            height = Math.max(0, height + velocity);
            velocity = (velocity - 0.08) * (double) 0.98F;
            f.player.fallDistance = (float) (drop - height);
            if (f.world.water && height < 0.8) { f.player.wet = true; f.player.fallDistance = 0; }
        }
        check(f.uses == 1 && f.world.water, "exact native outline ray must place once before support impact");
        f.position(0, 0, true); f.player.wet = true; f.player.fallDistance = 0;
        for (int i = 0; i < 45 && !f.session.complete(); i++) f.tick();
        check(f.session.complete() && !f.session.failed() && f.uses == 2 && !f.world.water,
                "native source plus empty bucket, water contact, pickup and renewed support finish the session");
        check(f.player.getMainHandItem().is(Items.WATER_BUCKET) && f.session.drainChanges().size() == 2,
                "recovery restores the bucket and attributes exactly placement plus removal");
    }

    private static void missingEvidence() throws Exception {
        for (int missing = 0; missing < 3; missing++) {
            Fixture f = new Fixture(false);
            f.position(2, -1, false); f.player.setXRot(0);
            check(f.session.prepareAlreadyHeld(f.context), "held opportunity is ready");
            f.tick(); check(f.uses == 0, "nearby support alone cannot bypass the actual facing ray");
            f.player.setXRot(90); f.blockEvidence = missing != 0; f.inventoryEvidence = missing != 1;
            f.tick(); f.position(0, 0, true); f.player.fallDistance = 0;
            f.player.wet = missing != 2 && f.world.water;
            for (int i = 0; i < 45 && !f.session.complete(); i++) f.tick();
            check(f.session.complete() && f.session.failed(), "health staying full cannot replace block, inventory or water-contact evidence");
            if (missing < 2) check(f.uses == 1, "unconfirmed water must never be recovered or retried");
        }
    }

    private static void existingWater() throws Exception {
        Fixture f = new Fixture(true);
        f.position(0, 0, true); f.player.wet = true;
        check(f.session.prepare(f.context), "observed existing source is a valid landing");
        for (int i = 0; i < 15; i++) f.tick();
        check(f.session.complete() && !f.session.failed() && f.uses == 0 && f.world.water,
                "existing water never grants pickup ownership");
    }

    private static void postRemovalSupport() throws Exception {
        Fixture f = new Fixture(false);
        f.position(2, -1, false); f.player.setXRot(90);
        check(f.session.prepareAlreadyHeld(f.context), "held opportunity is ready"); f.tick();
        f.position(0.2, 0, false); f.player.wet = true; f.player.fallDistance = 0;
        while (f.uses < 2 && f.time < 30) f.tick();
        check(f.uses == 2, "stable water contact starts recovery");
        for (int i = 0; i < 15; i++) f.tick();
        check(!f.session.complete(), "a stale wet flag after pickup cannot prove supported feet");
        f.position(0, 0, true);
        for (int i = 0; i < 12; i++) f.tick();
        check(f.session.complete() && !f.session.failed(), "renewed collision support completes recovery dwell");
    }

    private static void stalePreparation() throws Exception {
        Fixture f = new Fixture(false); f.position(12, 0, true);
        check(f.session.prepareAlreadyHeld(f.context) && f.session.prepare(f.context), "held departure prepared");
        f.player.inventory.setItem(0, ItemStack.EMPTY);
        check(!f.session.prepare(f.context) && f.session.failed() && f.uses == 0,
                "losing the selected bucket revokes departure readiness on supported ground");
    }

    private static void window() {
        var normal = new WaterLandingWindow(0.08, 4.5, 1.62, 0);
        check(normal.permits(20) && !normal.permits(200), "native reach and dry velocity bound the action window");
        check(new WaterLandingWindow(0.08, 6, 1.62, 0).permits(200), "real extended reach permits tall falls");
        check(new WaterLandingWindow(0.01, 4.5, 1.62, 0).permits(200), "real lower gravity changes the admissible window");
        check(!new WaterLandingWindow(0.08, 4.5, 1.62, 3.9).permits(4), "ongoing high speed cannot be treated as a fresh fall");
        // Sweep fractional departure heights independently using native gravity and exact voxel
        // rays. Every admitted sample must expose a use ray before the subsequent ground step.
        Scene scene = new Scene();
        for (int offset = 0; offset < 100; offset++) {
            double y = 20 + offset / 100.0, speed = 0; boolean reachable = false;
            while (y > 0) {
                Vec3 eye = new Vec3(0.5, y + 1.62, 0.5);
                reachable |= scene.clip(new ClipContext(eye, eye.add(0, -4.5, 0), ClipContext.Block.OUTLINE,
                        ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty())).getType() == HitResult.Type.BLOCK;
                y += speed; speed = (speed - 0.08) * (double) 0.98F;
            }
            check(normal.permits(20 + offset / 100.0) && reachable, "admitted fractional phase skipped the bucket reach window");
        }
    }

    static final class Fixture {
        final Unsafe memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        final TestPlayer player = (TestPlayer) memory.allocateInstance(TestPlayer.class);
        final FlatLevel world = (FlatLevel) memory.allocateInstance(FlatLevel.class);
        final Minecraft minecraft = (Minecraft) memory.allocateInstance(Minecraft.class);
        final DefaultNativeActionPort receipts = new DefaultNativeActionPort();
        final LocalPlayerContext context;
        final LandingAssistSession session;
        long time; int uses, selections; boolean blockEvidence = true, inventoryEvidence = true;
        Fixture(boolean existing) throws Exception {
            world.scene = new Scene(); world.water = existing;
            world.dimension = (net.minecraft.world.level.dimension.DimensionType) memory.allocateInstance(net.minecraft.world.level.dimension.DimensionType.class);
            player.health = 20;
            player.inventory = new Inventory(player); player.inventory.setItem(0, new ItemStack(Items.WATER_BUCKET));
            field(LocalPlayer.class, "abilities").set(player, new Abilities());
            field(LocalPlayer.class, "dimensions").set(player, EntityDimensions.scalable(0.6F, 1.8F));
            field(LocalPlayer.class, "eyeHeight").setFloat(player, 1.62F);
            field(LocalPlayer.class, "level").set(player, world);
            var body = (org.maiwithu.maicraft.client.actor.BodyControlPort) Proxy.newProxyInstance(
                    org.maiwithu.maicraft.client.actor.BodyControlPort.class.getClassLoader(),
                    new Class<?>[]{org.maiwithu.maicraft.client.actor.BodyControlPort.class}, (proxy, method, args) -> {
                        if (method.getName().equals("requestLook")) { player.setYRot((Float) args[0]); player.setXRot((Float) args[1]); }
                        if (method.getName().equals("applySteering")) ((org.maiwithu.maicraft.client.actor.BodyControlPort.Steering) args[0]).atYaw((Float) args[1]);
                        return null;
                    });
            NativeActionPort actions = (NativeActionPort) Proxy.newProxyInstance(NativeActionPort.class.getClassLoader(),
                    new Class<?>[]{NativeActionPort.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "useItem" -> use((NativeConfirmation) args[2], (Integer) args[3]);
                        case "selectHotbar" -> {
                            selections++; int slot = (Integer) args[1]; player.inventory.selected = slot;
                            yield receipt(NativeActionReceipt.Kind.SELECT_HOTBAR,
                                    c -> c.player().getInventory().selected == slot ? NativeConfirmation.Verdict.APPLIED : NativeConfirmation.Verdict.PENDING,
                                    (Integer) args[2]);
                        }
                        case "poll" -> receipts.poll((LocalPlayerContext) args[0], (NativeActionReceipt) args[1]);
                        default -> throw new AssertionError("unexpected native mutation: " + method.getName());
                    });
            context = (LocalPlayerContext) Proxy.newProxyInstance(LocalPlayerContext.class.getClassLoader(),
                    new Class<?>[]{LocalPlayerContext.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "player" -> player; case "level" -> world; case "minecraft" -> minecraft;
                        case "actions" -> actions; case "connection" -> null;
                        case "body" -> body;
                        case "permitsNativeActions", "mutationAvailable", "isCurrent" -> true;
                        case "bodyEpoch", "controlRevision" -> 1L; case "tickRevision" -> time;
                        case "requireCurrent" -> null;
                        default -> throw new AssertionError("unexpected context access: " + method.getName());
                    });
            session = new LandingAssistSession(new LandingAssistPlan(LandingAssistPlan.Kind.WATER,
                    BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO.below(), Direction.UP, existing));
        }
        private NativeActionReceipt use(NativeConfirmation confirmation, int timeout) throws Exception {
            uses++;
            boolean pickup = player.getMainHandItem().is(Items.BUCKET);
            BlockHitResult ray = world.clip(new ClipContext(player.getEyePosition(),
                    player.getEyePosition().add(player.getViewVector(1).scale(player.blockInteractionRange())),
                    ClipContext.Block.OUTLINE, pickup ? ClipContext.Fluid.SOURCE_ONLY : ClipContext.Fluid.NONE, player));
            check(ray.getType() == HitResult.Type.BLOCK && ray.getBlockPos().equals(pickup ? BlockPos.ZERO : BlockPos.ZERO.below()),
                    "submission must use the same native bucket ray as preparation");
            if (blockEvidence) world.water = !pickup;
            if (inventoryEvidence) player.inventory.setItem(player.inventory.selected, new ItemStack(pickup ? Items.WATER_BUCKET : Items.BUCKET));
            return receipt(NativeActionReceipt.Kind.USE_ITEM, confirmation, timeout);
        }
        private NativeActionReceipt receipt(NativeActionReceipt.Kind kind, NativeConfirmation confirmation, int timeout) throws Exception {
            var ctor = NativeActionReceipt.class.getDeclaredConstructors()[0]; ctor.setAccessible(true);
            var receipt = (NativeActionReceipt) ctor.newInstance(kind, context,
                    timeout, 2, confirmation, null, null);
            field(DefaultNativeActionPort.class, "active").set(receipts, receipt);
            return receipt;
        }
        void tick() { time++; session.tick(context); }
        void position(double height, double velocity, boolean grounded) throws Exception {
            field(LocalPlayer.class, "position").set(player, new Vec3(0.5, height, 0.5));
            field(LocalPlayer.class, "blockPosition").set(player, BlockPos.containing(0.5, height, 0.5));
            field(LocalPlayer.class, "deltaMovement").set(player, new Vec3(0, velocity, 0));
            field(LocalPlayer.class, "onGround").setBoolean(player, grounded);
            field(LocalPlayer.class, "bb").set(player, new net.minecraft.world.phys.AABB(0.2, height, 0.2, 0.8, height + 1.8, 0.8));
        }
    }
    static final class TestPlayer extends LocalPlayer {
        Inventory inventory; boolean wet; float health, hayMultiplier;
        private TestPlayer() { super(null, null, null, null, null, false, false); }
        public float getHealth() { return health; } public float getAbsorptionAmount() { return 0; }
        public boolean isSwimming() { return false; } public boolean onClimbable() { return false; }
        public boolean causeFallDamage(float distance, float multiplier, net.minecraft.world.damagesource.DamageSource source) {
            hayMultiplier = multiplier; health -= (float) Math.max(0, Math.ceil((distance - 3) * multiplier)); return true;
        }
        public boolean isInWater() { return wet; } public boolean isDescending() { return false; }
        public Inventory getInventory() { return inventory; }
        public ItemStack getMainHandItem() { return inventory.getSelected(); }
        public ItemStack getOffhandItem() { return ItemStack.EMPTY; }
        public ItemStack getItemInHand(InteractionHand hand) { return hand == InteractionHand.MAIN_HAND ? getMainHandItem() : getOffhandItem(); }
        public double blockInteractionRange() { return 4.5; }
    }
    static final class FlatLevel extends ClientLevel {
        Scene scene; boolean water; BlockHitResult nativeHit; net.minecraft.world.level.dimension.DimensionType dimension;
        private FlatLevel() { super(null, null, null, null, 0, 0, null, null, false, 0); }
        public boolean isLoaded(BlockPos pos) { return true; }
        public net.minecraft.world.level.dimension.DimensionType dimensionType() { return dimension; }
        public BlockState getBlockState(BlockPos pos) { scene.water = water; return scene.getBlockState(pos); }
        public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        public BlockHitResult clip(ClipContext context) { scene.water = water; return nativeHit != null ? nativeHit : scene.clip(context); }
        public int getHeight() { return 384; } public int getMinBuildHeight() { return -64; }
    }
    static final class Scene implements BlockGetter {
        boolean water; BlockState aid;
        public BlockState getBlockState(BlockPos pos) {
            if (aid != null && pos.equals(BlockPos.ZERO)) return aid;
            return (water && pos.equals(BlockPos.ZERO) ? Blocks.WATER : pos.getY() < 0 ? Blocks.STONE : Blocks.AIR).defaultBlockState();
        }
        public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        public int getHeight() { return 384; } public int getMinBuildHeight() { return -64; }
    }
    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            try { var field = owner.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
