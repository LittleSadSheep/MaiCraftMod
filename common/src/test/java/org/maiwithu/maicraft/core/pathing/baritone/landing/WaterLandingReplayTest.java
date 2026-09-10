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

/**
 * 逐刻给出下落高度，让真实落地会话、确认记录和方块射线一起运行；倒水、背包变化和入水状态由测试控制。覆盖成功、缺证据、旧水、回收后支撑与延迟受伤。
 */
public final class WaterLandingReplayTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        for (int drop : new int[]{4, 12, 20, 24}) replay(drop);
        missingEvidence(); existingWater(); postRemovalSupport(); stalePreparation(); delayedFailedHealth(); shortFallAim(); window();
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

    // 依次扣掉方块变化、背包变化、实际入水三类证据；单凭没有掉血不能算救援成功。
    private static void missingEvidence() throws Exception {
        for (int missing = 0; missing < 3; missing++) {
            Fixture f = new Fixture(false);
            f.position(2, -1, false); f.player.setXRot(0);
            check(f.session.prepareAlreadyHeld(f.context), "held opportunity is ready");
            f.world.scene.blocks.put(BlockPos.ZERO.above(),Blocks.STONE.defaultBlockState());
            f.tick(); check(f.uses == 0, "urgent aim cannot place through an actual occluding block");
            f.world.scene.blocks.remove(BlockPos.ZERO.above());
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

    // 入水后要先有可靠支撑才回收；舀掉水后再等站稳，旧的湿身状态不能替代新支撑。
    private static void postRemovalSupport() throws Exception {
        Fixture f = new Fixture(false);
        f.position(2, -1, false); f.player.setXRot(90);
        check(f.session.prepareAlreadyHeld(f.context), "held opportunity is ready"); f.tick();
        f.position(0.2, 0, false); f.player.wet = true; f.player.fallDistance = 0;
        for(int i=0;i<20;i++) f.tick();
        check(f.uses==1 && f.session.wantsSneak(f.context),"floating water contact retains its source and sinks toward supported ground");
        f.position(0,0,true);
        while (f.uses < 2 && f.time < 50) f.tick();
        check(f.uses == 2, "grounded supported dwell starts recovery");
        f.position(.2,0,false);
        for (int i = 0; i < 15; i++) f.tick();
        check(!f.session.complete(), "a stale wet flag after pickup cannot prove supported feet");
        f.position(0, 0, true);
        for (int i = 0; i < 12; i++) f.tick();
        check(f.session.complete() && !f.session.failed(), "renewed collision support completes recovery dwell");
        for(int i=0;i<15;i++) f.tick();
        check(f.uses==2 && f.session.diagnostics().get("placement_submissions").equals(1)
                && f.session.diagnostics().get("pickup_submissions").equals(1),"one fall never pours again after its one pickup");
    }
    private static void shortFallAim() throws Exception {
        var f=new Fixture(false); f.position(4,0,false); f.player.setYRot(73); f.player.setXRot(0);
        java.util.List<Float> yaws=new java.util.ArrayList<>(); int[] urgent={0};
        var body=(org.maiwithu.maicraft.client.actor.BodyControlPort)Proxy.newProxyInstance(
                org.maiwithu.maicraft.client.actor.BodyControlPort.class.getClassLoader(),
                new Class<?>[]{org.maiwithu.maicraft.client.actor.BodyControlPort.class},(proxy,method,args)-> {
                    if(method.getName().equals("requestLook") || method.getName().equals("requestImmediateLook")) yaws.add((Float)args[0]);
                    if(method.getName().equals("requestImmediateLook")) {
                        urgent[0]++; f.player.setYRot((Float)args[0]); f.player.setXRot((Float)args[1]);
                    }
                    return null; // No render frame is available to advance ordinary camera smoothing.
                });
        var context=(LocalPlayerContext)Proxy.newProxyInstance(LocalPlayerContext.class.getClassLoader(),new Class<?>[]{LocalPlayerContext.class},
                (proxy,method,args)->method.getName().equals("body") ? body : method.invoke(f.context,args));
        check(f.session.prepareAlreadyHeld(context),"short-fall bucket already held");
        double y=4,v=0;
        while(y>0 && f.uses==0) { f.position(y,v,false); f.time++; f.session.tick(context); y+=v; v=(v-.08)*.98; }
        check(f.uses==1 && urgent[0]>0 && !f.player.onGround(),"low fall uses the native bucket in the same tick as urgent aim");
        check(yaws.stream().allMatch(yaw->Math.abs(yaw-73)<.001),"water rescue never spins horizontal heading while looking down");
    }

    private static void stalePreparation() throws Exception {
        Fixture f = new Fixture(false); f.position(12, 0, true);
        check(f.session.prepareAlreadyHeld(f.context) && f.session.prepare(f.context), "held departure prepared");
        f.player.inventory.setItem(0, ItemStack.EMPTY);
        check(!f.session.prepare(f.context) && f.session.failed() && f.uses == 0,
                "losing the selected bucket revokes departure readiness on supported ground");
    }

    private static void delayedFailedHealth() throws Exception {
        Fixture f = new Fixture(false); f.position(12, 0, true);
        check(f.session.prepareAlreadyHeld(f.context), "remember health before a failed fall");
        f.position(4, -1, false); f.player.setXRot(0); f.tick();
        f.position(0, 0, true); f.player.fallDistance = 0; f.tick();
        check(f.session.failed() && !f.session.complete() && f.uses == 0,
                "first dry contact cannot finalize before synchronized health arrives");
        f.tick(); f.player.health = 1.52F; f.tick();
        for (int i = 0; i < 15 && !f.session.complete(); i++) f.tick();
        check(f.session.complete() && f.session.failed()
                        && ((Number) f.session.diagnostics().get("health_lost")).floatValue() > 18.47F,
                "the failed-touchdown dwell observes delayed native injury instead of freezing health_lost at zero");
    }

    private static void window() {
        var normal = new WaterLandingWindow(0.08, 4.5, 1.62, 0);
        check(normal.permits(20) && normal.permits(24) && !normal.permits(200), "native reach and dry velocity bound the action window");
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

    // 多数落地测试共用这个内存场景；原生操作在这里被替换为可控状态变化，确认记录仍使用项目真实实现。
    static final class Fixture {
        final Unsafe memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        final TestPlayer player = (TestPlayer) memory.allocateInstance(TestPlayer.class);
        final FlatLevel world = (FlatLevel) memory.allocateInstance(FlatLevel.class);
        final Minecraft minecraft = (Minecraft) memory.allocateInstance(Minecraft.class);
        final DefaultNativeActionPort receipts = new DefaultNativeActionPort();
        final LocalPlayerContext context;
        final LandingAssistSession session;
        long time; int uses, selections, bodyWrites; boolean blockEvidence = true, inventoryEvidence = true;
        org.maiwithu.maicraft.client.actor.BodyControlPort.Movement steering;
        Fixture(boolean existing) throws Exception {
            world.scene = new Scene(); world.water = existing;
            world.dimension = (net.minecraft.world.level.dimension.DimensionType) memory.allocateInstance(net.minecraft.world.level.dimension.DimensionType.class);
            player.health = 20;
            player.sources = (net.minecraft.world.damagesource.DamageSources) memory.allocateInstance(net.minecraft.world.damagesource.DamageSources.class);
            field(net.minecraft.world.damagesource.DamageSources.class, "fall").set(player.sources,
                    new net.minecraft.world.damagesource.DamageSource(net.minecraft.core.Holder.direct(
                            new net.minecraft.world.damagesource.DamageType("fall", 0))));
            player.inventory = new Inventory(player); player.inventory.setItem(0, new ItemStack(Items.WATER_BUCKET));
            field(LocalPlayer.class, "abilities").set(player, new Abilities());
            field(LocalPlayer.class, "dimensions").set(player, EntityDimensions.scalable(0.6F, 1.8F));
            field(LocalPlayer.class, "eyeHeight").setFloat(player, 1.62F);
            field(LocalPlayer.class, "level").set(player, world);
            var body = (org.maiwithu.maicraft.client.actor.BodyControlPort) Proxy.newProxyInstance(
                    org.maiwithu.maicraft.client.actor.BodyControlPort.class.getClassLoader(),
                    new Class<?>[]{org.maiwithu.maicraft.client.actor.BodyControlPort.class}, (proxy, method, args) -> {
                        if (method.getName().equals("requestLook") || method.getName().equals("requestImmediateLook")) { player.setYRot((Float) args[0]); player.setXRot((Float) args[1]); }
                        if (method.getName().equals("applySteering")) {
                            bodyWrites++; steering = ((org.maiwithu.maicraft.client.actor.BodyControlPort.Steering) args[0]).atYaw((Float) args[1]);
                        }
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
        // 复查瞄准射线后按测试开关改变水和背包；可故意缺一项变化，验证上层不会误报成功。
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
        public boolean isCreative() { return false; }
        public boolean isSpectator() { return false; }
        public void awardStat(net.minecraft.stats.Stat<?> stat, int amount) { }
        public void playSound(net.minecraft.sounds.SoundEvent sound, float volume, float pitch) { }
        public boolean mayUseItemAt(BlockPos pos, Direction face, ItemStack stack) { return true; }
        Inventory inventory; boolean wet; float health, hayMultiplier;
        net.minecraft.world.damagesource.DamageSources sources;
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
        public double entityInteractionRange() { return 3; }
        public net.minecraft.world.entity.EntityType<?> getType() { return net.minecraft.world.entity.EntityType.PLAYER; }
        public net.minecraft.world.damagesource.DamageSources damageSources() { return sources; }
        public net.minecraft.world.effect.MobEffectInstance getEffect(net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect) { return null; }
        public boolean hasEffect(net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect) { return false; }
        public ItemStack getItemBySlot(net.minecraft.world.entity.EquipmentSlot slot) { return ItemStack.EMPTY; }
        public double getAttributeValue(net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute) {
            var key = attribute.unwrapKey().orElseThrow();
            if (key.equals(net.minecraft.world.entity.ai.attributes.Attributes.GRAVITY.unwrapKey().orElseThrow())) return 0.08;
            if (key.equals(net.minecraft.world.entity.ai.attributes.Attributes.SAFE_FALL_DISTANCE.unwrapKey().orElseThrow())) return 3;
            if (key.equals(net.minecraft.world.entity.ai.attributes.Attributes.FALL_DAMAGE_MULTIPLIER.unwrapKey().orElseThrow())) return 1;
            throw new AssertionError("unexpected attribute " + key);
        }
    }
    static final class FlatLevel extends ClientLevel {
        public boolean noCollision(net.minecraft.world.entity.Entity entity,net.minecraft.world.phys.AABB body) {
            for(BlockPos pos:BlockPos.betweenClosed(BlockPos.containing(body.minX-1,body.minY-1,body.minZ-1),BlockPos.containing(body.maxX+1,body.maxY+1,body.maxZ+1)))
                for(var shape:getBlockState(pos).getCollisionShape(this,pos).toAabbs())
                    if(shape.move(pos).intersects(body)) return false;
            return true;
        }
        public java.util.List<net.minecraft.world.entity.Entity> getEntities(net.minecraft.world.entity.Entity except,
                net.minecraft.world.phys.AABB box,java.util.function.Predicate<? super net.minecraft.world.entity.Entity> predicate) {
            return observedEntities==null ? java.util.List.of() : observedEntities.stream().filter(entity->entity!=except)
                    .filter(entity->entity.getBoundingBox().intersects(box)).filter(predicate).toList();
        }
        public boolean mayInteract(net.minecraft.world.entity.player.Player player, BlockPos pos) { return true; }
        public boolean setBlock(BlockPos pos, BlockState state, int flags) { scene.blocks.put(pos.immutable(),state); return true; }
        public void scheduleTick(BlockPos pos, net.minecraft.world.level.material.Fluid fluid, int delay) { }
        public void playSound(net.minecraft.world.entity.player.Player player, BlockPos pos, net.minecraft.sounds.SoundEvent sound,
                net.minecraft.sounds.SoundSource source, float volume, float pitch) { }
        public void gameEvent(net.minecraft.core.Holder<net.minecraft.world.level.gameevent.GameEvent> event, Vec3 position,
                net.minecraft.world.level.gameevent.GameEvent.Context context) { }
        Scene scene; boolean water; BlockHitResult nativeHit; net.minecraft.world.level.dimension.DimensionType dimension;
        java.util.List<net.minecraft.world.entity.Entity> observedEntities;
        private FlatLevel() { super(null, null, null, null, 0, 0, null, null, false, 0); }
        public boolean isLoaded(BlockPos pos) { return true; }
        public net.minecraft.world.level.dimension.DimensionType dimensionType() { return dimension; }
        public BlockState getBlockState(BlockPos pos) { scene.water = water; return scene.getBlockState(pos); }
        public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        public BlockHitResult clip(ClipContext context) { scene.water = water; return nativeHit != null ? nativeHit : scene.clip(context); }
        public <T extends net.minecraft.world.entity.Entity> java.util.List<T> getEntitiesOfClass(Class<T> type,
                net.minecraft.world.phys.AABB bounds, java.util.function.Predicate<? super T> predicate) {
            return observedEntities == null ? java.util.List.of() : observedEntities.stream().filter(type::isInstance)
                    .map(type::cast).filter(entity -> entity.getBoundingBox().intersects(bounds)).filter(predicate).toList();
        }
        public int getHeight() { return 384; } public int getMinBuildHeight() { return -64; }
    }
    static final class Scene implements BlockGetter {
        boolean water; BlockState aid;
        final java.util.Map<BlockPos, BlockState> blocks = new java.util.HashMap<>();
        public BlockState getBlockState(BlockPos pos) {
            if (blocks.containsKey(pos)) return blocks.get(pos);
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
