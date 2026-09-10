package org.maiwithu.maicraft.core.pathing.baritone.landing;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;

/**
 * 检查落地上船的放置范围、上船距离、实体归属、下船空间和回收条件；部分几何直接与原版帮助方法对照。
 */
public final class BoatLandingAssistTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        check(Boat.class.getMethod("causeFallDamage", float.class, float.class, DamageSource.class).getDeclaringClass() == Entity.class,
                "boat inherits passenger fall-damage propagation; it is not blanket damage immunity");
        for (float yaw : new float[]{0, 45, 90, 180, 270, -75}) {
            check(BoatLandingGeometry.dismountOffset(1.375, 0.6, yaw).distanceTo(NativeEscape.offset(1.375, 0.6, yaw)) < 1.0E-8,
                    "dismount projection differs from the native escape vector");
        }
        var view = new Flat(); var source = new BlockPos(0,2,0); var landing = new BlockPos(1,1,0);
        var eye = new Vec3(0.5,3.62,0.5); var spawn = new Vec3(1.5,1,0.5);
        var snapshot = new BoatLandingSnapshot(source, eye, 4.5, 3, 0.6, 1.8, Items.OAK_BOAT, List.of());
        check(snapshot.plan(view, source, landing, p -> true) != null, "reachable pre-placement and boarding corridor");
        check(snapshot.plan(view, source.east(), landing, p -> true) == null, "worker cannot invent a remote player departure");
        check(snapshot.plan(view, source, landing, p -> p.getX() != 1) == null, "unloaded landing is unavailable");
        var high = new BoatLandingSnapshot(new BlockPos(0,5,0), new Vec3(0.5,6.62,0.5), 8, 3, 0.6, 1.8, Items.OAK_BOAT, List.of());
        check(high.plan(view, high.source(), landing, p -> true) == null, "block reach alone does not establish native mount reach");
        var extended = new BoatLandingSnapshot(high.source(), high.eye(), 8, 6, 0.6, 1.8, Items.OAK_BOAT, List.of());
        check(extended.plan(view, extended.source(), landing, p -> true) != null, "actual extended interaction range replaces a fixed fall-height ban");
        UUID old = UUID.randomUUID(), created = UUID.randomUUID();
        var facts = new ArrayList<BoatLandingSnapshot.BoatFact>(); facts.add(new BoatLandingSnapshot.BoatFact(old, spawn, BoatLandingGeometry.boatBox(spawn),0));
        var existing = new BoatLandingSnapshot(source, eye, 4.5, 3, 0.6, 1.8, null, facts); facts.clear();
        check(existing.plan(view, source, landing, p -> true).existingBoat().equals(old), "frozen observed boat can be used without a carried item");
        check(!BoatLandingSnapshot.plainBoat(Items.OAK_CHEST_BOAT) && BoatLandingSnapshot.plainBoat(Items.BAMBOO_RAFT), "only supported ordinary boat item behavior");
        check(BoatLandingAssist.placementEvidence(Set.of(old), List.of(old), 1, 0, false).verdict() == NativeConfirmation.Verdict.PENDING,
                "inventory use cannot claim a pre-existing boat");
        check(BoatLandingAssist.placementEvidence(Set.of(old), List.of(created), 1, 1, false).verdict() == NativeConfirmation.Verdict.PENDING,
                "new entity alone cannot prove inventory-backed placement");
        check(BoatLandingAssist.placementEvidence(Set.of(old), List.of(created), 1, 0, false).uuid().equals(created), "unique native entity plus consumption confirms identity");
        check(BoatLandingAssist.placementEvidence(Set.of(old), List.of(created, UUID.randomUUID()), 1, 0, false).verdict() == NativeConfirmation.Verdict.DIVERGED,
                "ambiguous concurrent spawns must not assign ownership");
        check(snapshot.airborneWindow(4,.08,0),"a planned short descent has native spawn and mount steps");
        check(!snapshot.airborneWindow(200,.08,0),"a planned fatal leap cannot assume instant boat creation and boarding");
        check(BoatLandingRecovery.capacity(net.minecraft.world.item.ItemStack.EMPTY, new net.minecraft.world.item.ItemStack(Items.OAK_BOAT)),
                "empty main-inventory slot can receive the native boat drop");
        check(!BoatLandingRecovery.capacity(new net.minecraft.world.item.ItemStack(Items.OAK_BOAT), new net.minecraft.world.item.ItemStack(Items.OAK_BOAT)),
                "a full nonstackable boat slot is not pickup capacity");
        check(!BoatLandingRecovery.recoveredIntoInventory(false,0,1), "boat destruction alone must never confirm recovery");
        check(!BoatLandingRecovery.recoveredIntoInventory(true,1,1), "unrelated inventory growth cannot recover a still-existing boat");
        check(BoatLandingRecovery.recoveredIntoInventory(false,1,1), "recovery requires both removal and restored item count");
        check(!BoatLandingAssist.damageFree(20,2,20,1), "absorbed damage must not be called damage-free");
        check(!BoatLandingAssist.damageFree(20,2,21,1), "healing cannot conceal spent absorption");
        check(!BoatLandingAssist.damageFree(20,2,19,3), "new absorption cannot conceal lost health");
        check(BoatLandingAssist.damageFree(20,2,20,2), "unchanged health and absorption verify no observed damage");
        rejectsCreativeRecovery();
        System.out.println("BoatLandingAssistTest: passed");
    }
    private static void rejectsCreativeRecovery() throws Exception {
        var memoryField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); memoryField.setAccessible(true);
        var player = (CreativePlayer)((sun.misc.Unsafe)memoryField.get(null)).allocateInstance(CreativePlayer.class);
        var ctx = (org.maiwithu.maicraft.client.actor.LocalPlayerContext)java.lang.reflect.Proxy.newProxyInstance(
                org.maiwithu.maicraft.client.actor.LocalPlayerContext.class.getClassLoader(),
                new Class<?>[]{org.maiwithu.maicraft.client.actor.LocalPlayerContext.class}, (proxy,method,args) -> {
                    if (method.getName().equals("player")) return player;
                    throw new AssertionError("creative recovery queried a world/action: " + method.getName());
                });
        var eligible = BoatLandingRecovery.class.getDeclaredMethod("eligible", org.maiwithu.maicraft.client.actor.LocalPlayerContext.class, Boat.class);
        eligible.setAccessible(true);
        check(!(Boolean)eligible.invoke(new BoatLandingRecovery(UUID.randomUUID(),Items.OAK_BOAT),ctx,null),
                "creative recovery must stop before any boat, inventory or native-action access");
    }
    private static final class CreativePlayer extends net.minecraft.client.player.LocalPlayer {
        private CreativePlayer() { super(null,null,null,null,null,false,false); }
        public boolean isCreative() { return true; }
    }
    private static void check(boolean ok, String detail) { if (!ok) throw new AssertionError(detail); }
    private static final class NativeEscape extends Entity {
        private NativeEscape() { super(EntityType.BOAT, null); }
        static Vec3 offset(double boat, double rider, float yaw) {
            return getCollisionHorizontalEscapeVector(boat * net.minecraft.util.Mth.SQRT_OF_TWO, rider, yaw);
        }
        protected void defineSynchedData(SynchedEntityData.Builder builder) { }
        protected void readAdditionalSaveData(CompoundTag tag) { }
        protected void addAdditionalSaveData(CompoundTag tag) { }
    }
    private static final class Flat implements BlockGetter {
        public BlockState getBlockState(BlockPos pos) { return pos.getY() == 0 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(); }
        public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        public int getHeight() { return 384; }
        public int getMinBuildHeight() { return -64; }
    }
}
