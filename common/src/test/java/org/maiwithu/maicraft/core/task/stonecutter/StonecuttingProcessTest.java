// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.stonecutter;

import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.player.Player;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.integration.machine.process.MinecraftStonecuttingProcessAdapter;
import org.maiwithu.maicraft.core.integration.machine.process.NativeProcessRegistry;
import org.maiwithu.maicraft.task.TaskState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.StonecutterMenu;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.maiwithu.maicraft.core.task.base.MenuTransferEvidence;
import org.maiwithu.maicraft.task.TaskResult;
import java.lang.reflect.Proxy;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuPort;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.actor.MenuConfirmation;

/**
 * 石切机原生工序回归：参数校验与钳制、适配器匹配与任务创建、消费命名空间对齐，
 * 以及伴生任务在缺料或站点丢失时的诚实失败路径。菜单内切制以实机验收为准。
 */
public final class StonecuttingProcessTest {
    private static final BlockPos STATION = new BlockPos(2, 1, 2);
    private static final long DEADLINE = 6000;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        parametersRejectInvalidAndOutOfRange();
        adapterMatchesStationAndCreatesTypedRecord();
        registryResolvesProcessWithMatchingNamespace();
        companionFailsHonestlyWithoutMaterial();
        companionFailsHonestlyWithoutStation();
        arrivalRebasesStock(); transferFailureRetainsFacts();
        rejectedSelectionRefreshesWithoutConsumption();
        System.out.println("StonecuttingProcessTest: passed");
    }

    private static void arrivalRebasesStock() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.inventory.setItem(0, new ItemStack(Items.STONE, 4));
            var stock = StonecuttingStock.prepare(h.player, ResourceLocation.parse("minecraft:stone"), ResourceLocation.parse("minecraft:stone_bricks"), 4);
            h.inventory.setItem(0, new ItemStack(Items.STONE, 6)); h.inventory.setItem(1, new ItemStack(Items.STONE_BRICKS, 2));
            var menu = new StonecutterMenu(4, h.inventory); stock.loadMoves(menu);
            // 到站之前拾取的材料不归因给切制；这里仅回放账本，原生菜单点击由事务回归另行验证。
            h.inventory.setItem(0, new ItemStack(Items.STONE, 2)); h.inventory.setItem(1, new ItemStack(Items.STONE_BRICKS, 6));
            check(stock.outputDelta() == 4 && stock.verifiedAfterCrafts(menu), "对账基线应取首次装料之前的真实库存");
        }
    }

    private static void transferFailureRetainsFacts() {
        var ledger = new MenuTransferEvidence(); var facts = Map.<String, Object>of("moved_counts", List.of(2, 1),
                "completed_moves", 1, "effects_started", true, "outcome_uncertain", true);
        ledger.retain("load_input", TaskResult.fail("原生搬运尚未确认", facts));
        var data = new LinkedHashMap<String, Object>(); data.put("outcome_uncertain", false); data.put("mechanical_retry_allowed", true);
        ledger.appendTo(data);
        var row = (Map<?, ?>) ((List<?>) data.get("transfer_results")).getFirst();
        check(row.get("data").equals(facts) && Boolean.TRUE.equals(data.get("outcome_uncertain"))
                && Boolean.FALSE.equals(data.get("mechanical_retry_allowed")), "父加工回执保留部分搬运和禁止重放的真实原因");
        check(MenuTransferEvidence.canRefreshBeforeSubmission(TaskResult.fail("source changed", Map.of("effects_started", false,
                        "outcome_uncertain", false, "submitted_clicks", 0)))
                && !MenuTransferEvidence.canRefreshBeforeSubmission(TaskResult.fail("pending click", Map.of("effects_started", true,
                        "outcome_uncertain", true, "submitted_clicks", 1))), "只有完整的零提交证明允许父流程重选槽位");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void rejectedSelectionRefreshesWithoutConsumption() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.inventory.setItem(0, new ItemStack(Items.STONE, 4));
            var record = new StonecuttingTaskRecord("selection", 1000, StonecuttingParameters.parse(params("minecraft:stone", "minecraft:stone_bricks", 4, null)), STATION);
            var menu = new StonecutterMenu(4, h.inventory); h.player.containerMenu = menu;
            var stock = StonecuttingStock.prepare(h.player, record.input, record.output, record.count);
            var flow = new StonecutterMenuFlow(h.player, record, menu, stock, () -> true);
            MenuPort port = (MenuPort) Proxy.newProxyInstance(MenuPort.class.getClassLoader(), new Class<?>[]{MenuPort.class},
                    (proxy, method, args) -> { if (method.getName().equals("poll")) return args[1]; throw new AssertionError("不得直接重放取件: " + method.getName()); });
            LocalPlayerContext context = (LocalPlayerContext) Proxy.newProxyInstance(LocalPlayerContext.class.getClassLoader(), new Class<?>[]{LocalPlayerContext.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "menus" -> port; case "permitsNativeActions" -> true;
                        case "bodyEpoch", "controlRevision", "tickRevision" -> 1L;
                        default -> throw new AssertionError("unexpected context: " + method.getName());
                    });
            var constructor = MenuReceipt.class.getDeclaredConstructor(MenuReceipt.Kind.class, LocalPlayerContext.class,
                    int.class, int.class, int.class, int.class, boolean.class, MenuConfirmation.class); constructor.setAccessible(true);
            var phase = StonecutterMenuFlow.class.getDeclaredField("phase"); phase.setAccessible(true);
            var selection = StonecutterMenuFlow.class.getDeclaredField("selectionReceipt"); selection.setAccessible(true);
            var finish = MenuReceipt.class.getDeclaredMethod("finish", MenuReceipt.Status.class, String.class); finish.setAccessible(true);
            for (int attempt = 0; attempt < 3; attempt++) {
                var receipt = constructor.newInstance(MenuReceipt.Kind.BUTTON, context, 4, 0, -1, 100, false, null);
                finish.invoke(receipt, MenuReceipt.Status.CONFIRMED_NOT_APPLIED, "native selection rejected before submission");
                phase.set(flow, Enum.valueOf((Class) phase.getType(), "WAIT_BUTTON")); selection.set(flow, receipt);
                check(flow.tick(context) == (attempt < 2 ? TaskState.RUNNING : TaskState.FAILED)
                        && Boolean.FALSE.equals(flow.data().get("outcome_uncertain")), "未生效选方只有限刷新，同步拒绝不变成未知消费");
            }
            check(h.inventory.countItem(Items.STONE) == 4 && flow.data().get("recipe_selection_refreshes").equals(2), "刷新没有消费原料或无限重试");
        }
    }

    private static void parametersRejectInvalidAndOutOfRange() {
        var adapter = new MinecraftStonecuttingProcessAdapter();
        try {
            adapter.validate(params("minecraft:stone", "minecraft:stone_bricks", 4, "extra"));
            check(false, "foreign parameter keys must be rejected");
        } catch (IllegalArgumentException expected) { }
        try {
            adapter.validate(params("minecraft:not_an_item", "minecraft:stone_bricks", 1, null));
            check(false, "unregistered input must be rejected");
        } catch (IllegalArgumentException expected) { }
        try {
            adapter.validate(params("minecraft:stone", null, 1, null));
            check(false, "a missing output_item_id must be rejected");
        } catch (IllegalArgumentException expected) { }
        try {
            StonecuttingParameters.parse(params("minecraft:stone", "minecraft:stone_bricks", 999, null));
            check(false, "an out-of-range count must be rejected, not silently clamped");
        } catch (IllegalArgumentException expected) { }
    }

    private static void adapterMatchesStationAndCreatesTypedRecord() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var adapter = new MinecraftStonecuttingProcessAdapter();
            check(!adapter.matches(h.player, STATION), "an unloaded stonecutter must not match");
            h.set(STATION, Blocks.STONECUTTER.defaultBlockState());
            check(adapter.matches(h.player, STATION), "the placed stonecutter matches at its exact position");
            var record = adapter.createTask("stonecutting-test", DEADLINE, h.player, STATION,
                    params("minecraft:stone", "minecraft:stone_bricks", 4, null));
            check(record instanceof StonecuttingTaskRecord, "the process creates a typed submission record");
            var stonecutting = (StonecuttingTaskRecord) record;
            check(stonecutting.input.toString().equals("minecraft:stone")
                    && stonecutting.output.toString().equals("minecraft:stone_bricks")
                    && stonecutting.count == 4 && stonecutting.station.equals(STATION),
                    "the record freezes input, output, count and station");
            check("stonecutting".equals(stonecutting.submissionNamespace()),
                    "the record carries the registered consumption namespace");
        }
    }

    private static void registryResolvesProcessWithMatchingNamespace() {
        var adapter = NativeProcessRegistry.adapter(MinecraftStonecuttingProcessAdapter.ID);
        check(adapter.id().equals("minecraft:stonecutting"), "the registry resolves the stonecutting mechanism");
        check("stonecutting".equals(NativeProcessRegistry.consumptionNamespace(MinecraftStonecuttingProcessAdapter.ID)),
                "the consumption namespace is registered for the durable barrier");
    }

    private static void companionFailsHonestlyWithoutMaterial() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            prepareDefaultMenus(h);
            h.set(STATION, Blocks.STONECUTTER.defaultBlockState());
            var record = new StonecuttingTaskRecord("stonecutting-test", DEADLINE,
                    StonecuttingParameters.parse(params("minecraft:stone", "minecraft:stone_bricks", 4, null)), STATION);
            var task = new StonecuttingCompanionTask(h.player, record);
            task.start(h.player);
            check(task.tick(h.player) == TaskState.FAILED, "missing input material fails before any body action");
            check("stonecutting_input_material_missing".equals(task.result(TaskState.FAILED).data().get("issue_code")),
                    "the failure names the missing material");
        }
    }

    private static void companionFailsHonestlyWithoutStation() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            prepareDefaultMenus(h);
            h.inventory.setItem(0, new ItemStack(Items.STONE, 8));
            var record = new StonecuttingTaskRecord("stonecutting-test", DEADLINE,
                    StonecuttingParameters.parse(params("minecraft:stone", "minecraft:stone_bricks", 4, null)), STATION);
            var task = new StonecuttingCompanionTask(h.player, record);
            task.start(h.player);
            check(task.tick(h.player) == TaskState.FAILED, "a missing station fails before any body action");
            check("stonecutter_station_missing_or_unloaded".equals(task.result(TaskState.FAILED).data().get("issue_code")),
                    "the failure names the missing station");
        }
    }

    /** Unsafe 分配的玩家没有运行构造函数；生产玩家默认 containerMenu == inventoryMenu，任务启动前同样要先就位。 */
    private static void prepareDefaultMenus(InteractionWorldTestHarness h) throws Exception {
        var menu = new InventoryMenu(h.inventory, true, h.player);
        var field = Player.class.getDeclaredField("inventoryMenu");
        field.setAccessible(true);
        field.set(h.player, menu);
        h.player.containerMenu = menu;
    }

    private static JsonObject params(String input, String output, int count, String extraKey) {
        var parameters = new JsonObject();
        if (input != null) parameters.addProperty("item_id", input);
        if (output != null) parameters.addProperty("output_item_id", output);
        parameters.addProperty("count", count);
        if (extraKey != null) parameters.addProperty(extraKey, "unexpected");
        return parameters;
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
