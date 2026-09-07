package org.maiwithu.maicraft.core.pathing.baritone.landing;

import java.lang.reflect.Field;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.core.pathing.baritone.FallDamageBudget;
import org.maiwithu.maicraft.core.task.chain.MLGChain;

/** Planned and unexpected triggers both drive the production placement/contact/recovery state machine. */
public final class SharedLandingExecutionTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        water(false, true); water(true, true); water(true, false); hay(); rejectsPlotStorageRay();
        System.out.println("SharedLandingExecutionTest: passed");
    }

    private static void water(boolean emergency, boolean inventoryEvidence) throws Exception {
        var f = new WaterLandingReplayTest.Fixture(false);
        var reflex = new MLGChain();
        f.position(12, 0, true);
        if (emergency) {
            f.player.inventory.setItem(0, ItemStack.EMPTY);
            f.player.inventory.setItem(3, new ItemStack(Items.WATER_BUCKET));
        } else check(f.session.prepareAlreadyHeld(f.context) && f.session.prepare(f.context), "planned pre-equipment");
        f.inventoryEvidence = inventoryEvidence;
        LandingAssistSession session = f.session;
        double y = 12, velocity = -0.8;
        while (y > 0) {
            f.position(y, velocity, false);
            if (emergency) {
                check(reflex.canRun(f.player), "unexpected fall and its in-flight receipt keep the reflex active");
                f.time++; reflex.tick(f.context);
                session = (LandingAssistSession) field(MLGChain.class, "session").get(reflex);
                check(session != null, "native support rays selected an emergency landing plan");
            } else { f.player.setXRot(90); f.tick(); }
            check(!session.complete(), "neither trigger may end before actual touchdown");
            y = Math.max(0, y + velocity); velocity = (velocity - 0.08) * (double) 0.98F;
            f.player.fallDistance = (float) (12 - y);
            if (f.world.water && y < 0.8) { f.player.wet = true; f.player.fallDistance = 0; }
        }
        check(f.uses == 1 && f.world.water, "both triggers submitted one reachable native placement");
        f.position(0, 0, true); f.player.wet = true; f.player.fallDistance = 0;
        for (int i = 0; i < 60 && !session.complete(); i++) {
            if (emergency) { check(reflex.canRun(f.player), "recovery retains the emergency owner"); f.time++; reflex.tick(f.context); }
            else f.tick();
        }
        check(session.complete(), "shared session must terminate after its bounded evidence windows");
        if (inventoryEvidence) {
            check(!session.failed() && f.uses == 2 && !f.world.water && f.player.getMainHandItem().is(Items.WATER_BUCKET),
                    "both triggers require source, bucket, native water contact, pickup and fresh support");
            check(Boolean.TRUE.equals(session.diagnostics().get("native_water_contact"))
                    && Boolean.TRUE.equals(session.diagnostics().get("removed_own_aid")), "shared authoritative evidence recorded");
        } else check(session.failed() && f.uses == 1 && f.world.water,
                "the emergency trigger cannot bypass missing inventory evidence or recover unowned water");
        check(f.selections == (emergency ? 1 : 0), "only emergency hotbar preparation selected an item");
        if (emergency) check(!reflex.canRun(f.player), "confirmed or failed terminal session yields the reflex owner");
    }

    private static void hay() throws Exception {
        var f = new WaterLandingReplayTest.Fixture(false);
        var plan = new LandingAssistPlan(LandingAssistPlan.Kind.HAY, BlockPos.ZERO.above(), BlockPos.ZERO,
                BlockPos.ZERO.below(), Direction.UP, true);
        check(plan.survives(budget(4), 17, false) && !plan.survives(budget(3), 17, false),
                "native 0.2 cushioning may save a fall, but an equal-to-health hit remains fatal");
        var waterOnly = new LandingAssistPlan.InventorySnapshot(Set.of(LandingAssistPlan.Kind.HAY), true, false, false);
        check(waterOnly.plans(f.world, BlockPos.ZERO, p -> false).isEmpty(), "water-only permission cannot authorize hay");
        var landing = new LandingAssistPlan.InventorySnapshot(Set.of(LandingAssistPlan.Kind.HAY), true, true, false);
        check(landing.plans(f.world, BlockPos.ZERO, p -> false).stream().anyMatch(p -> p.kind() == LandingAssistPlan.Kind.HAY),
                "general landing assistance retains the native hay option");
        f.world.scene.aid = Blocks.HAY_BLOCK.defaultBlockState();
        f.position(1, 0, true);
        field(Level.class, "damageSources").set(f.world, f.memory.allocateInstance(DamageSources.class));
        var session = new LandingAssistSession(plan);
        f.time++; session.tick(f.context);
        // HayBlock.fallOn itself passes 0.2 to the native damage callback. The fixture applies
        // ordinary ceil damage there; the production budget independently checks the same hit.
        Blocks.HAY_BLOCK.fallOn(f.world, Blocks.HAY_BLOCK.defaultBlockState(), BlockPos.ZERO, f.player, 16);
        check(f.player.hayMultiplier == 0.2F && f.player.getHealth() == 17, "native hay behavior must cushion rather than reset damage");
        for (int i = 0; i < 12; i++) { f.time++; session.tick(f.context); }
        check(session.complete() && !session.failed() && Boolean.TRUE.equals(session.diagnostics().get("mitigated_with_damage"))
                        && ((Number) session.diagnostics().get("health_lost")).floatValue() == 3,
                "confirmed hay support and survival succeed while explicitly reporting the observed injury");
        check(f.uses == 0 && f.world.scene.aid.is(Blocks.HAY_BLOCK), "existing hay gains no removal ownership");
    }

    private static FallDamageBudget budget(float health) {
        return new FallDamageBudget(health, 0, 3, 1, 0, 0, 0, 0, 0.08, 0, false);
    }
    private static void rejectsPlotStorageRay() throws Exception {
        var f = new WaterLandingReplayTest.Fixture(false);
        f.position(12, -1, false);
        // Native integrations may return both location and cell in storage space, or retain
        // the world intersection while returning only the storage cell. Neither is usable here.
        var storage = new BlockPos(28_000_000, 70, 28_000_000);
        for (var location : new net.minecraft.world.phys.Vec3[]{
                net.minecraft.world.phys.Vec3.atCenterOf(storage), new net.minecraft.world.phys.Vec3(0.5, 0, 0.5)}) {
            f.world.nativeHit = new net.minecraft.world.phys.BlockHitResult(location, Direction.UP, storage, false);
            check(EmergencyLanding.find(f.context) == null && f.uses == 0 && f.selections == 0,
                    "plot-storage hits cannot become global landing goals or native item operations");
            check("unsupported".equals(LandingAssistPolicy.diagnosticState().get("support_state")),
                    "unverified physical support must report its unsupported coordinate frame");
        }
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
