// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.supply;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.preview.PreviewSession.Decision;
import org.maiwithu.maicraft.core.pathing.settings.ScaffoldMaterials;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.task.build.BuildCompanionTask;
import org.maiwithu.maicraft.core.task.build.BuildExcavationSpoilSupply;
import org.maiwithu.maicraft.core.task.build.BuildSupplyAccessTest;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.task.container.ContainerSupplySources;
import org.maiwithu.maicraft.core.task.container.ContainerSupplySourcesTest;
import org.maiwithu.maicraft.core.task.container.SemanticContainerTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;
import org.maiwithu.maicraft.core.PlayerInv;

/** 推进真实供料父任务验证出坑、存余料、取建材、续建的顺序；模拟库存回执不代表真实 GUI 验收。 */
public final class BuildSupplyCargoDispatchTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var runners = runners(); var buildBefore = runners.get(BuildTaskRecord.class); var containerBefore = runners.get(SemanticContainerTaskRecord.class);
        List<String> priorScaffolds = ScaffoldMaterials.storedIds(null);
        try {
            ScaffoldMaterials.store(null, ScaffoldMaterials.factoryDefaultIds());
            TaskFactory.register(BuildTaskRecord.class, BuildCompanionTask::new);
            TaskFactory.register(SemanticContainerTaskRecord.class, DepositFixture::new);
            fullResumedInventoryLeavesThePitAndStoresBeforeFetching();
            carriedOnlyDoesNotOpenStorage();
            carriedWorkPrecedesAnUnnecessarySpoilTrip();
        } finally {
            if (buildBefore == null) runners.remove(BuildTaskRecord.class); else runners.put(BuildTaskRecord.class, buildBefore);
            if (containerBefore == null) runners.remove(SemanticContainerTaskRecord.class); else runners.put(SemanticContainerTaskRecord.class, containerBefore);
            ScaffoldMaterials.store(null, priorScaffolds);
            ContainerSupplySources.reset();
        }
        System.out.println("BuildSupplyCargoDispatchTest: passed");
    }

    private static void fullResumedInventoryLeavesThePitAndStoresBeforeFetching() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var task = task(h, SemanticMaterialSupplyCoordinator.MaterialPolicy.STORAGE_AVAILABLE);
            var entities = ContainerSupplySourcesTest.worldEntities(h);
            ContainerSupplySourcesTest.addBarrel(h, entities, new BlockPos(1, 4, 4));
            h.inventory.setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
            for (int slot = 1; slot <= 24; slot++) h.inventory.setItem(slot, new ItemStack(Items.DIRT, 64));
            for (int slot = 25; slot < 36; slot++) h.inventory.setItem(slot, new ItemStack(Items.BREAD, 64));
            task.start(h.player); task.tick(h.player);
            var supply = (SemanticMaterialSupplyCoordinator) field(task, "supply").get(task);
            var spoil = (BuildExcavationSpoilSupply) field(task, "spoilSupply").get(task);
            check(((BuildTaskRecord) field(task, "activeRecord").get(task)).supplyAccessOnly() && !supply.active() && !spoil.active(),
                    "满包使可取数量为零时仍先出坑，不能直接报告没有空间或在坑里找箱子");

            // 测试注入已到地面的身体观察，再让实际出口任务确认，父任务随后才开始整理。
            h.position(new Vec3(2.5, 4, 4.5)); h.nextTick();
            for (int i = 0; i < 4 && field(task, "activeChild").get(task) != null; i++) { task.tick(h.player); h.nextTick(); }
            task.tick(h.player);
            check(spoil.active() && !supply.active() && task.progress().get("phase").equals("storing_excavation_spoil"),
                    "出坑确认之后进入存余料阶段，尚未开始取建材");
            task.tick(h.player);
            check(task.mustSettleBeforeSatisfiedCancellation(), "存入子任务未收尾时保留父任务的确认屏障");
            check(Boolean.FALSE.equals(task.resultData().get("goal_satisfied")), "整理中不能因别的条件满足就提前宣布项目完成");
            for (int i = 0; i < 5 && spoil.active(); i++) { task.tick(h.player); h.nextTick(); }
            check(!spoil.active() && !supply.active(), "真实背包差量与模拟存入回执一致后，先结束整理再派发取料");
            check(spoil.receipt().get("confirmed_deposited").equals(Map.of("minecraft:dirt", 1472))
                    && count(h.player, Items.DIRT) == 64 && count(h.player, Items.BREAD) == 704,
                    "只存多余泥土，默认一组支撑及全部食物工具仍在背包");
            var capacity = SemanticBuildSupplyCompanionTask.class.getDeclaredMethod("capacityFor", Item.class); capacity.setAccessible(true);
            check((int) capacity.invoke(task, Items.OAK_PLANKS) == 19 * 64, "清出的二十三格中至少保留四格给掉落与中间材料");
            task.tick(h.player);
            check(supply.active() && !spoil.active(), "完成整理后才请求缺少的九块木板");
            var rounds = (List<?>) task.resultData().get("batches");
            check(rounds.size() == 2 && ((Map<?, ?>) rounds.get(0)).get("kind").equals("build_access")
                    && ((Map<?, ?>) rounds.get(1)).get("kind").equals("excavation_spoil"), "出坑和存余料分别记录，不能冒充施工批次");

            // 测试模拟随后已经取到建材，父任务必须转回普通施工，不能再次出坑或重复存相同余料。
            supply.cancel(h.player); h.inventory.setItem(1, new ItemStack(Items.OAK_PLANKS, 9)); h.nextTick(); task.tick(h.player);
            check(!((BuildTaskRecord) field(task, "activeRecord").get(task)).supplyAccessOnly()
                    && field(task, "buildRounds").getInt(task) == 1, "拿到建材后续建，原存入回执不重放");
            task.result(TaskState.CANCELLED);
        }
    }

    private static void carriedOnlyDoesNotOpenStorage() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var task = task(h, SemanticMaterialSupplyCoordinator.MaterialPolicy.INVENTORY_ONLY);
            h.inventory.setItem(0, new ItemStack(Items.DIRT, 64)); h.inventory.setItem(1, new ItemStack(Items.DIRT, 64));
            task.start(h.player);
            var prepare = SemanticBuildSupplyCompanionTask.class.getDeclaredMethod("prepareCargo"); prepare.setAccessible(true);
            check(!(boolean) prepare.invoke(task) && !((BuildExcavationSpoilSupply) field(task, "spoilSupply").get(task)).active()
                    && count(h.player, Items.DIRT) == 128, "只用背包策略不会为了整理擅自使用仓库");
            task.result(TaskState.CANCELLED);
        }
    }
    private static SemanticBuildSupplyCompanionTask task(InteractionWorldTestHarness h, SemanticMaterialSupplyCoordinator.MaterialPolicy policy) throws Exception {
        // 模拟原生背包刚打开又关闭后的空游标，夹具跳过菜单构造时也不能留下非游戏状态的 null。
        h.player.inventoryMenu.setCarried(ItemStack.EMPTY);
        var plan = new BuildTaskRecord("resumed-cargo", 1000, BuildSupplyAccessTest.preparePit(h), false, true);
        var record = new SemanticBuildSupplyTaskRecord("resumed-cargo-supply", 1000, plan, policy,
                policy == SemanticMaterialSupplyCoordinator.MaterialPolicy.INVENTORY_ONLY ? List.of() : List.of(SemanticAcquireTaskRecord.Source.STORAGE), false, List.of(), false);
        return new SemanticBuildSupplyCompanionTask(h.player, record, (owner, frozen) -> Decision.DISABLED);
    }
    private static void carriedWorkPrecedesAnUnnecessarySpoilTrip() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var task = task(h, SemanticMaterialSupplyCoordinator.MaterialPolicy.STORAGE_AVAILABLE);
            // 重放地下室内材料已齐、三十三泥土作支撑、十一圆石待存的状态；空背包不能为了小批余料打断通路施工。
            h.inventory.setItem(0, new ItemStack(Items.DIRT, 33)); h.inventory.setItem(1, new ItemStack(Items.COBBLESTONE, 11));
            h.inventory.setItem(2, new ItemStack(Items.OAK_PLANKS, 9)); var origin=h.player.position();
            task.start(h.player); task.tick(h.player);
            var spoil=(BuildExcavationSpoilSupply)field(task,"spoilSupply").get(task);
            check(field(task,"activeRecord").get(task) instanceof BuildTaskRecord build && !build.supplyAccessOnly()
                            && !spoil.active() && !((SemanticMaterialSupplyCoordinator)field(task,"supply").get(task)).active(),
                    "enough carried materials and spare slots start construction before a small surplus trip");
            check(Boolean.TRUE.equals(task.resultData().get("cleanup_deferred")) && count(h.player,Items.COBBLESTONE)==11
                            && h.player.position().equals(origin) && h.blockUses()==0 && h.itemUses()==0,
                    "deferral retains the real cargo and cannot claim deposited items or move the body");
            // 模拟施工已确认完成并来到外部地面，再推进真实整理入口，证明延期不会变成永久忘记存放。
            var plan=(BuildTaskRecord)field(task,"activePlan").get(task);
            plan.targets.forEach(target->h.set(target.pos(),target.desiredState())); h.position(new Vec3(7.5,1,4.5)); h.nextTick();
            var prepare=SemanticBuildSupplyCompanionTask.class.getDeclaredMethod("prepareCargo");prepare.setAccessible(true);
            check((boolean)prepare.invoke(task) && spoil.active(),"the finished-work boundary returns to the deferred surplus");
            task.result(TaskState.CANCELLED);
        }
    }
    private static final class DepositFixture implements Task {
        private final LocalPlayer player; private final SemanticContainerTaskRecord record; private int ticks, moved;
        DepositFixture(LocalPlayer player, SemanticContainerTaskRecord record) { this.player = player; this.record = record; }
        public TaskState tick(LocalPlayer ignored) {
            if (++ticks == 1) return TaskState.RUNNING;
            int left = record.count;
            for (int slot = 0; slot < 36 && left > 0; slot++) {
                ItemStack stack = player.getInventory().getItem(slot); if (!stack.is(Items.DIRT)) continue;
                int take = Math.min(left, stack.getCount()); stack.shrink(take); moved += take; left -= take;
            }
            return TaskState.SUCCESS;
        }
        public void stop(LocalPlayer player, StopReason reason) {}
        public boolean mustSettleBeforeSatisfiedCancellation() { return ticks < 2; }
        public String name() { return "模拟可见界面已确认存入"; }
        public TaskResult result(TaskState state) { return TaskResult.ok("模拟存入", Map.of("operation", "deposit", "bounded_storage_deposit", true,
                "moved_count", moved, "moved_items", moved == 0 ? Map.of() : Map.of("minecraft:dirt", moved), "outcome_uncertain", false)); }
    }
    private static int count(LocalPlayer player, Item item) { return PlayerInv.buildableCount(player.getInventory(), item); }
    @SuppressWarnings("unchecked") private static Map<Class<? extends TaskRecord>, TaskFactory.Runner<? extends TaskRecord>> runners() throws Exception {
        return (Map<Class<? extends TaskRecord>, TaskFactory.Runner<? extends TaskRecord>>) field(TaskFactory.class, "RUNNERS").get(null);
    }
    private static Field field(Object instance, String name) throws Exception {
        Class<?> type = instance instanceof Class<?> value ? value : instance.getClass(); Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
