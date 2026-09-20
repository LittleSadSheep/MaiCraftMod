// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.inventory.EatCompanionTask;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** 吃饭前后只认观察到的扣物与饥饿变化；夹具模拟回执，不把模拟吃饭当作真人持用证据。 */
public final class BuildFoodPreparationTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        thresholdsAndOrdinaryFood();
        refillAndObserveRecovery();
        injuredBodyRestoresRegenerationFood();
        neverAcceptAClaimWithoutFoodEvidence();
        missingFoodAndUnchangingHealthAreBounded();
        directNativeChildAndCancellation();
        System.out.println("BuildFoodPreparationTest: passed");
    }

    private static void thresholdsAndOrdinaryFood() {
        check(BuildFoodPreparation.needs(false, 14, 20, 20) && !BuildFoodPreparation.needs(false, 15, 20, 20), "ordinary work starts food preparation at fourteen hunger");
        check(BuildFoodPreparation.needs(false, 20, 1, 20) && !BuildFoodPreparation.needs(true, 0, 1, 20), "a fed critical body still rests; creative mode does not manufacture meals");
        check(BuildFoodPreparation.needs(false, 17, 11, 20) && !BuildFoodPreparation.needs(false, 18, 11, 20)
                && !BuildFoodPreparation.needs(false, 17, 20, 20), "injury below the natural-regeneration hunger threshold is a separate reason to eat");
        check(BuildFoodPreparation.needs(false, 17, 20, 40), "injury uses the actual maximum health rather than assuming twenty health is always full");
        var stocks = new ArrayList<ItemStack>();
        stocks.add(new ItemStack(Items.CHORUS_FRUIT)); stocks.add(new ItemStack(Items.SUSPICIOUS_STEW));
        stocks.add(new ItemStack(Items.GOLDEN_APPLE)); stocks.add(new ItemStack(Items.ROTTEN_FLESH)); stocks.add(new ItemStack(Items.HONEY_BOTTLE));
        check(BuildFoodPreparation.choose(stocks, 0) == null, "teleporting, effectful and special-use food cannot be consumed as automatic upkeep");
        stocks.add(new ItemStack(Items.BREAD, 64));
        check(BuildFoodPreparation.choose(stocks, 0) == Items.BREAD && stocks.getLast().getCount() == 64, "ordinary bread is selected without changing inventory");
        var altered = new ItemStack(Items.BREAD); altered.set(DataComponents.FOOD, new ItemStack(Items.ROTTEN_FLESH).get(DataComponents.FOOD)); stocks.add(altered);
        check(BuildFoodPreparation.choose(stocks, 0) == null, "mixed component variants of one item ID cannot trick an item-only eating task into choosing the wrong stack");
        stocks.clear(); for (int slot = 0; slot < 36; slot++) stocks.add(ItemStack.EMPTY); stocks.add(new ItemStack(Items.BREAD));
        check(BuildFoodPreparation.choose(stocks, 0) == null, "food outside the selectable main inventory is not claimed available");
    }

    private static void refillAndObserveRecovery() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.player.setHealth(1); h.player.getFoodData().setFoodLevel(0); h.inventory.setItem(0, new ItemStack(Items.BREAD, 64));
            var prep = new BuildFoodPreparation((player, record) -> new ObservedMeal()); var owner = owner();
            for (int step = 0; step < 8; step++) {
                h.nextTick(); check(prep.tick(h.player, owner, child -> {
                    h.inventory.getItem(0).shrink(1); h.player.getFoodData().setFoodLevel(Math.min(20, h.player.getFoodData().getFoodLevel() + 5));
                    return TaskState.SUCCESS;
                }) == BuildFoodPreparation.Status.RUNNING, "food receipt settlement must yield before subsequent work");
            }
            check(h.inventory.getItem(0).getCount() == 60 && h.player.getFoodData().getFoodLevel() == 20
                    && h.player.getHealth() == 1 && prep.receipts().size() == 4, "four finite bread items restore hunger, never health by assignment in the coordinator");
            h.nextTick(); check(prep.tick(h.player, owner, child -> { throw new AssertionError("no more food required"); }) == BuildFoodPreparation.Status.RUNNING,
                    "full food does not let a one-health body resume excavation");
            h.player.setHealth(5); h.nextTick();
            check(prep.tick(h.player, owner, child -> null) == BuildFoodPreparation.Status.RUNNING, "partial natural recovery keeps construction paused");
            h.player.setHealth(8); h.nextTick();
            check(prep.tick(h.player, owner, child -> null) == BuildFoodPreparation.Status.READY && !prep.active(), "observed safe health permits the original work to continue");
            check(prep.progress(h.player).get("confirmed_food_items").equals(4)
                    && prep.receipts().stream().allMatch(receipt -> Boolean.TRUE.equals(receipt.get("confirmed"))), "confirmed food actions retain counts and observed body evidence");
        }
        try (var h = new InteractionWorldTestHarness()) {
            h.player.getFoodData().setFoodLevel(14); h.inventory.setItem(0, new ItemStack(Items.BREAD, 2));
            var prep = new BuildFoodPreparation((p, r) -> new ObservedMeal()); prep.tick(h.player, owner(), child -> null);
            prep.tick(h.player, owner(), child -> { h.inventory.getItem(0).shrink(1); h.player.getFoodData().setFoodLevel(19); return TaskState.SUCCESS; });
            check(prep.tick(h.player, owner(), child -> null) == BuildFoodPreparation.Status.READY && h.inventory.getItem(0).getCount() == 1,
                    "a healthy body stops at near-full hunger instead of eating every available item");
        }
    }

    private static void neverAcceptAClaimWithoutFoodEvidence() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.player.getFoodData().setFoodLevel(10); h.inventory.setItem(0, new ItemStack(Items.BREAD, 2));
            var prep = new BuildFoodPreparation((p, r) -> new ObservedMeal()); prep.tick(h.player, owner(), child -> null);
            check(prep.tick(h.player, owner(), child -> TaskState.SUCCESS) == BuildFoodPreparation.Status.FAILED
                    && prep.failure().equals("build_food_unconfirmed") && h.inventory.getItem(0).getCount() == 2,
                    "a claimed eating success without actual item and hunger changes cannot restart work");
        }
    }

    private static void injuredBodyRestoresRegenerationFood() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            // 重放私有实机的十一点生命、十七饱食度和五十八面包；首个安全边界应正常吃一件，不直接加血。
            h.player.setHealth(11); h.player.getFoodData().setFoodLevel(17); h.inventory.setItem(0, new ItemStack(Items.BREAD, 58));
            var prep = new BuildFoodPreparation((p, r) -> new ObservedMeal()); var owner = owner();
            check(prep.shouldPrepare(h.player) && prep.tick(h.player, owner, child -> null) == BuildFoodPreparation.Status.RUNNING
                    && h.inventory.getItem(0).getCount() == 58 && h.player.getHealth() == 11,
                    "the live injury state must start preparation without predicting consumption or healing");
            h.nextTick();
            check(prep.tick(h.player, owner, child -> {
                h.inventory.getItem(0).shrink(1); h.player.getFoodData().setFoodLevel(20); return TaskState.SUCCESS;
            }) == BuildFoodPreparation.Status.RUNNING, "the normal native-food receipt settles before construction resumes");
            h.nextTick();
            check(prep.tick(h.player, owner, child -> { throw new AssertionError("one bread already restored hunger"); }) == BuildFoodPreparation.Status.READY
                    && h.inventory.getItem(0).getCount() == 57 && h.player.getHealth() == 11,
                    "a moderate injury resumes with regeneration food available, without waiting for or manufacturing full health");
            check(prep.receipts().size() == 1 && Boolean.TRUE.equals(prep.receipts().getFirst().get("confirmed"))
                    && prep.receipts().getFirst().get("food_before").equals(17) && prep.receipts().getFirst().get("food_after").equals(20),
                    "the injury-triggered meal keeps the same confirmed inventory and body evidence");
            for (int i = 0; i < 6; i++) { h.nextTick(); check(!prep.shouldPrepare(h.player), "already-fed moderate injury must not repeatedly consume bread"); }
        }
        try (var h = new InteractionWorldTestHarness()) {
            h.player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(40);
            h.player.setHealth(30); h.player.getFoodData().setFoodLevel(17);
            var prep = new BuildFoodPreparation((p, r) -> { throw new AssertionError("no food may be invented"); });
            check(prep.shouldPrepare(h.player) && prep.tick(h.player, owner(), child -> null) == BuildFoodPreparation.Status.FAILED
                    && prep.failure().equals("build_food_unavailable") && h.inventory.isEmpty() && h.player.getHealth() == 30,
                    "higher maximum health still detects injury, and missing food pauses without items or healing");
        }
    }

    private static void missingFoodAndUnchangingHealthAreBounded() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.player.getFoodData().setFoodLevel(0);
            var prep = new BuildFoodPreparation((p, r) -> { throw new AssertionError("no item may be created"); });
            check(prep.tick(h.player, owner(), child -> null) == BuildFoodPreparation.Status.FAILED
                    && prep.failure().equals("build_food_unavailable") && h.inventory.isEmpty(), "no food pauses construction without acquiring or creating an item");
        }
        try (var h = new InteractionWorldTestHarness()) {
            h.player.setHealth(1); var prep = new BuildFoodPreparation(); var owner = owner();
            check(prep.tick(h.player, owner, child -> null) == BuildFoodPreparation.Status.RUNNING, "full food permits a bounded natural-recovery observation");
            for (int step = 0; step < BuildFoodPreparation.RECOVERY_IDLE_TIMEOUT; step++) h.nextTick();
            check(prep.tick(h.player, owner, child -> null) == BuildFoodPreparation.Status.FAILED
                    && prep.failure().equals("build_health_recovery_unconfirmed") && h.player.getHealth() == 1,
                    "disabled or unavailable regeneration fails within a finite unchanged-health window");
        }
    }

    private static void directNativeChildAndCancellation() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.player.getFoodData().setFoodLevel(0); h.inventory.setItem(0, new ItemStack(Items.BREAD, 2));
            var nativePrep = new BuildFoodPreparation(); nativePrep.tick(h.player, owner(), child -> null);
            nativePrep.tick(h.player, owner(), child -> { check(child instanceof EatCompanionTask, "the default child is the native eater without TaskFactory registration"); return null; });
            nativePrep.stop(h.player);
            check(!nativePrep.active() && h.inventory.getItem(0).getCount() == 2, "stopping an unstarted native eater preserves its food");
            var held = new ObservedMeal(); var prep = new BuildFoodPreparation((p, r) -> held);
            prep.tick(h.player, owner(), child -> null); prep.tick(h.player, owner(), child -> { held.using = true; return null; });
            h.inventory.getItem(0).shrink(1); h.player.getFoodData().setFoodLevel(5); prep.stop(h.player);
            check(!held.using && held.stops == 1 && held.results == 1 && !prep.active(), "pause stops and finalizes only the owned in-flight food child");
            check(prep.receipts().getLast().get("observed_consumed").equals(1)
                    && !Boolean.TRUE.equals(prep.receipts().getLast().get("confirmed")), "an interrupted action retains its observed consumption without asserting no effect or confirmed completion");
        }
    }

    private static BuildTaskRecord owner() { return new BuildTaskRecord("food-upkeep", 1000, List.of(), false, true); }
    private static final class ObservedMeal implements Task {
        boolean using; int stops, results;
        public TaskState tick(LocalPlayer player) { return TaskState.RUNNING; }
        public void stop(LocalPlayer player, StopReason why) { stops++; using = false; }
        public TaskResult result(TaskState state) { results++; using = false; return state == TaskState.SUCCESS ? TaskResult.ok("observed fixture meal") : TaskResult.fail("interrupted fixture meal"); }
        public String name() { return "observed meal fixture"; }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
