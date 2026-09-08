package org.maiwithu.maicraft.core.pathing.baritone.landing;

import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.BodyControlPort;
import org.maiwithu.maicraft.client.actor.DefaultBodyControlPort;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.core.task.craft.CraftCompanionTask;
import org.maiwithu.maicraft.core.task.craft.CraftTaskRecord;
import org.maiwithu.maicraft.task.TaskState;

/** Prepare one landing boat, then reuse the native spawn/mount/dismount controller. */
final class LandingBoatRescue {
    private static final List<Item> ITEMS = List.of(Items.OAK_BOAT,Items.SPRUCE_BOAT,Items.BIRCH_BOAT,
            Items.JUNGLE_BOAT,Items.ACACIA_BOAT,Items.DARK_OAK_BOAT,Items.MANGROVE_BOAT,Items.CHERRY_BOAT,Items.BAMBOO_RAFT);
    private final LandingAssistPlan landing;
    private final LandingMaterialSupply supply = LandingMaterialSupply.boats(ITEMS.stream().map(BuiltInRegistries.ITEM::getKey).toList());
    private LandingPreparation preparation;
    private BoatLandingAssist boat;
    private CraftCompanionTask craft;
    private MenuReceipt closing;
    private boolean craftTried, departed, failed, stopped, ready;
    private BoatLandingAssist.State state = BoatLandingAssist.State.RUNNING;
    private String detail = "preparing a landing boat";
    LandingBoatRescue(LandingAssistPlan landing, boolean airborne) { this.landing=landing; departed=airborne; }

    static LandingAssistPlan plan(BlockGetter world, BlockPos feet, double width, double height) {
        Vec3 spawn = BoatLandingGeometry.support(world,pos -> true,feet);
        if (spawn == null || !BoatLandingGeometry.clear(world,pos -> true,BoatLandingGeometry.boatBox(spawn))
                || !BoatLandingGeometry.clear(world,pos -> true,new AABB(spawn.x-width/2,spawn.y+.563,spawn.z-width/2,
                        spawn.x+width/2,spawn.y+.563+height,spawn.z+width/2))
                || !BoatLandingGeometry.hasExit(world,pos -> true,spawn,width,height)) return null;
        return new LandingAssistPlan(LandingAssistPlan.Kind.BOAT,feet,feet,feet.below(),Direction.UP,false,spawn);
    }
    static Item carried(LocalPlayer player) {
        for (int slot=0;slot<player.getInventory().getContainerSize();slot++) {
            Item item=player.getInventory().getItem(slot).getItem();
            if (BoatLandingSnapshot.plainBoat(item)) return item;
        }
        return null;
    }
    boolean materialReady() { return ready && !failed; }
    boolean prepare(LocalPlayerContext context) { tick(context); return materialReady(); }
    void tick(LocalPlayerContext context) {
        if (stopped || failed) { cleanup(context); return; }
        departed |= !context.player().onGround();
        if (departed && context.player().onGround() && !context.player().isPassenger() && (boat==null || !boat.ready())) {
            failed=true; detail="ground contact preceded the native boat catch"; cleanup(context); return;
        }
        if (boat == null && !prepareMaterial(context)) return;
        if (!departed && context.player().onGround()) return;
        state = boat.tick(context); failed = boat.failed(); detail = boat.diagnostics().get("detail").toString();
    }
    private boolean prepareMaterial(LocalPlayerContext context) {
        if (closing != null) {
            closing=context.menus().poll(context,closing);
            if (!closing.terminal()) return false;
            if (closing.status()!=MenuReceipt.Status.CONFIRMED_APPLIED) { failed=true; return false; }
            closing=null;
        }
        int remaining = actionTicks(context);
        if (craft==null) for (Boat existing : context.level().getEntitiesOfClass(Boat.class,BoatLandingGeometry.boatBox(landing.aimPoint()).inflate(.5))) {
            if (existing.getClass()!=Boat.class || !BoatLandingSnapshot.stationary(existing) || !existing.getPassengers().isEmpty()
                    || existing.position().distanceToSqr(landing.aimPoint())>=.25
                    || !BoatLandingGeometry.hasExit(context.level(),context.level()::isLoaded,existing.position(),
                        context.player().getBbWidth(),context.player().getBbHeight(),existing.getYRot())) continue;
            if (!supply.result().finished()) { supply.finish(context,"using an observed landing boat"); if(supply.cleanupPending())return false; }
            boat=new BoatLandingAssist(new BoatLandingSnapshot.Plan(context.player().blockPosition(),landing.feet(),null,existing.getUUID(),existing.position(),true));
            ready=true; return true;
        }
        if (craft != null) {
            if (remaining <= 6) { stopCraft(context); return false; }
            var result=craft.tick(context.player());
            if (!result.isTerminal()) { detail="crafting a landing boat with the native recipe book"; return false; }
            var outcome=craft.result(result); craft=null;
            detail=outcome.message(); closeMenu(context); return false;
        }
        if (!supply.result().finished()) {
            var result=supply.tick(context,remaining); detail=result.detail();
            if (!result.finished()) return false;
        }
        Item item=carried(context.player());
        if (item==null) {
            if (!craftTried && !supply.craftSubmitted() && remaining>6 && startCraft(context)) return false;
            failed=true; LandingAssistPolicy.automaticSupplyFailed();
            detail="no landing boat became available before the native action window"; return false;
        }
        if (preparation==null) preparation=new LandingPreparation(item);
        boolean prepared=context.player().onGround() ? preparation.tick(context) : preparation.tickEmergency(context,remaining);
        if (!prepared) { failed=preparation.failed(); detail=preparation.diagnostic(); return false; }
        java.util.UUID existing=null;
        for (Boat candidate : context.level().getEntitiesOfClass(Boat.class,BoatLandingGeometry.boatBox(landing.aimPoint()).inflate(.5)))
            if (BoatLandingSnapshot.stationary(candidate) && candidate.getPassengers().isEmpty()
                    && candidate.position().distanceToSqr(landing.aimPoint())<.25) { existing=candidate.getUUID(); break; }
        boat=new BoatLandingAssist(new BoatLandingSnapshot.Plan(context.player().blockPosition(),landing.feet(),
                existing==null ? item : null,existing,landing.aimPoint(),true));
        ready=true; return true;
    }
    private boolean startCraft(LocalPlayerContext context) {
        craftTried=true;
        if (context.connection()==null) return false;
        var contents=new net.minecraft.world.entity.player.StackedContents();
        context.player().getInventory().fillStackedContents(contents);
        for (var recipe : context.connection().getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            var result=recipe.value().getResultItem(context.level().registryAccess());
            if (!BoatLandingSnapshot.plainBoat(result.getItem()) || !contents.canCraft(recipe.value(),null)) continue;
            BlockPos station=null; var eye=context.player().getEyePosition();
            for (BlockPos cell : BlockPos.betweenClosed(context.player().blockPosition().offset(-4,-3,-4),context.player().blockPosition().offset(4,3,4))) {
                if (context.level().isLoaded(cell) && context.level().getBlockState(cell).getBlock() instanceof CraftingTableBlock
                        && eye.distanceToSqr(Vec3.atCenterOf(cell))<context.player().blockInteractionRange()*context.player().blockInteractionRange()) {
                    var hit=context.level().clip(new net.minecraft.world.level.ClipContext(eye,Vec3.atCenterOf(cell),
                            net.minecraft.world.level.ClipContext.Block.OUTLINE,net.minecraft.world.level.ClipContext.Fluid.NONE,context.player()));
                    if(hit.getType()!=net.minecraft.world.phys.HitResult.Type.BLOCK || !hit.getBlockPos().equals(cell)) continue;
                    station=cell.immutable(); break;
                }
            }
            var record=new CraftTaskRecord("landing-boat",context.level().getGameTime()+Math.min(240,actionTicks(context)),
                    recipe.id(),1,1,result.getCount(),station).inPlace();
            craft=new CraftCompanionTask(context.player(),record); craft.start(context.player()); return true;
        }
        return false;
    }
    private int actionTicks(LocalPlayerContext context) {
        if (context.player().onGround() && !departed) return Integer.MAX_VALUE;
        double y=context.player().getY(), v=context.player().getDeltaMovement().y;
        double target=landing.aimPoint().y+context.player().blockInteractionRange()-context.player().getEyeHeight();
        double gravity=context.player().getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.GRAVITY);
        for (int ticks=0;ticks<200;ticks++) { if(y<=target)return ticks; y+=v; v=(v-gravity)*.98; }
        return 200;
    }
    private void closeMenu(LocalPlayerContext context) {
        if (!DefaultBodyControlPort.permitsWorldMovement(context.minecraft().screen)
                || context.player().containerMenu!=context.player().inventoryMenu)
            closing=context.menus().closeForTaskBoundary(context,20,"landing boat crafting ended");
    }
    private void stopCraft(LocalPlayerContext context) {
        if(craft!=null) { craft.stop(context.player(),org.maiwithu.maicraft.task.Task.StopReason.REPLACED); craft.result(TaskState.CANCELLED); craft=null; }
        closeMenu(context);
    }
    void stop(LocalPlayerContext context) { stopped=true; cleanup(context); }
    private void cleanup(LocalPlayerContext context) {
        if (!context.permitsNativeActions()) return;
        supply.finish(context,"landing boat preparation ended");
        if(craft!=null) stopCraft(context);
        if(closing!=null) closing=context.menus().poll(context,closing);
        if(preparation!=null) { preparation.closeForFailure(context); preparation.continueCleanup(context); }
        if(boat!=null) { boat.cancel(context); state=boat.tick(context); }
    }
    boolean complete(LocalPlayerContext context) { return state==BoatLandingAssist.State.SETTLED
            || (failed||stopped) && context.player().onGround() && !cleanupPending(); }
    boolean failed() { return failed||stopped; }
    boolean cleanupPending() { return supply.cleanupPending() || closing!=null&&!closing.terminal()
            || preparation!=null&&preparation.cleanupPending() || boat!=null&&boat.cleanupPending(); }
    Vec3 aimPoint() { return boat==null ? landing.aimPoint() : boat.aimPoint(); }
    boolean wantsSneak() { return boat!=null && boat.wantsSneak(); }
    BodyControlPort.Movement movementOverride() { return boat==null ? null : boat.movementOverride(); }
    Map<String,Object> diagnostics() { return Map.of("detail",detail,"material_supply",supply.result().detail(),
            "craft_attempted",craftTried,"boat",boat==null ? Map.of() : boat.diagnostics()); }
}
