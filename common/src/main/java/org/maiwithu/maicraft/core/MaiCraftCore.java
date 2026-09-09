package org.maiwithu.maicraft.core;

import org.maiwithu.maicraft.agent.tool.ToolRegistry;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.core.task.build.BuildCompanionTask;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.task.collect.CollectItemsCompanionTask;
import org.maiwithu.maicraft.core.task.collect.CollectItemsTaskRecord;
import org.maiwithu.maicraft.core.task.inventory.DropCompanionTask;
import org.maiwithu.maicraft.core.task.inventory.DropItemsTaskRecord;
import org.maiwithu.maicraft.core.task.inventory.EatCompanionTask;
import org.maiwithu.maicraft.core.task.inventory.EatItemTaskRecord;
import org.maiwithu.maicraft.core.task.inventory.EquipCompanionTask;
import org.maiwithu.maicraft.core.task.inventory.EquipTaskRecord;
import org.maiwithu.maicraft.core.task.fish.FishCompanionTask;
import org.maiwithu.maicraft.core.task.fish.FishTaskRecord;
import org.maiwithu.maicraft.core.task.combat.AttackCompanionTask;
import org.maiwithu.maicraft.core.task.combat.AttackTaskRecord;
import org.maiwithu.maicraft.core.task.interact.InteractAtCompanionTask;
import org.maiwithu.maicraft.core.task.interact.InteractAtTaskRecord;
import org.maiwithu.maicraft.core.task.interact.InteractEntityCompanionTask;
import org.maiwithu.maicraft.core.task.interact.InteractEntityTaskRecord;
import org.maiwithu.maicraft.core.task.locate.LocateBiomeCompanionTask;
import org.maiwithu.maicraft.core.task.locate.LocateBiomeTaskRecord;
import org.maiwithu.maicraft.core.task.locate.LocateStructureCompanionTask;
import org.maiwithu.maicraft.core.task.locate.LocateStructureTaskRecord;
import org.maiwithu.maicraft.core.task.mine.MineBlockTaskRecord;
import org.maiwithu.maicraft.core.task.mine.MineCompanionTask;
import org.maiwithu.maicraft.core.task.move.MoveToCompanionTask;
import org.maiwithu.maicraft.core.task.move.MoveToTaskRecord;

/**
 * 共享功能的总注册入口，由 Fabric 或 NeoForge 的客户端入口调用一次。
 *
 * <p>增加功能时，需要分清以下几种接线：
 * <ul>
 *   <li>{@link ToolRegistry}：内部操作名对应哪个工具，例如 goto；这里的工具不会全部直接公开给 MCP。</li>
 *   <li>{@link TaskFactory}：工具生成任务记录后，由哪个 Task 对象逐 tick 执行。</li>
 *   <li>生存反射：哪些紧急行为可以请求接管身体，以及它们的检查顺序。</li>
 * </ul>
 * 对外能力的参数说明在 SemanticAbilityCatalog，目标到内部操作的转换在各 AbilityAdapter。
 */
public final class MaiCraftCore {

    private static boolean initialised = false;

    private MaiCraftCore() {}

    // 启动时登记工具、任务执行器和三种紧急自救行为。标志一旦设为 true，本进程后续调用便不再登记。
    public static void init() {
        if (initialised) return;
        initialised = true;
        registerTools();
        registerTaskRunners();
        registerReflexes();
        enlistReflexRoster();
        Constants.LOG.info("[maicraft-core] registered {} tool(s), {} task type(s); survival chains enabled",
                ToolRegistry.size(), TaskFactory.size());
    }

    /**
     * 登记生存反射的工厂；实际运行的实例由 CompanionBrain 创建并参加身体调度。
     */
    private static void registerReflexes() {
        // 数字越小越先检查，固定顺序为落地救援、换气、自卫；这些数字不参与动态评分。
        //
        // 卡住后的绕路、挖路或垫脚交给当前寻路任务处理：它知道这次允不允许改地形、哪些格不能碰。
        // 这里不另加一条见到卡住就抢身体乱走的自救行为，以免打断本来正在完成的挖掘或跳跃。
        org.maiwithu.maicraft.task.BrainChains.register(10,
                org.maiwithu.maicraft.core.task.chain.MLGChain::new);
        org.maiwithu.maicraft.task.BrainChains.register(20,
                org.maiwithu.maicraft.core.task.chain.BreathChain::new);
        org.maiwithu.maicraft.task.BrainChains.register(30,
                org.maiwithu.maicraft.core.task.chain.MobDefenseChain::new);
    }

    /**
     * 登记反射的名称和说明。这里的实例只供查询，实际执行实例由上面的工厂创建。
     */
    private static void enlistReflexRoster() {
        org.maiwithu.maicraft.core.task.reflex.CoreReflexes.registerAll();
    }

    private static void registerTools() {

        // 工具负责接收参数、生成任务记录或即时结果；需要持续操作身体的功能还必须注册执行器。
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.work.MoveToTool());
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.work.AttackTool());
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.locate.LocateStructureTool());
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.locate.LocateBiomeTool());
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.work.CollectItemsTool());
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.work.FishTool());
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.work.FollowTool());
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.work.AutoMineTool());
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.inventory.EquipItemTool());
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.work.BuildTool());
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.work.BlueprintTool());
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.perception.BlueprintReadTool());
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.interact.InteractAtTool());
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.interact.SleepTool());
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.interact.InteractEntityTool());
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.inventory.EatItemTool());
        ToolRegistry.register(new org.maiwithu.maicraft.task.TaskStatusTool());
        ToolRegistry.register(new org.maiwithu.maicraft.task.TaskStopTool());
        ToolRegistry.register(new org.maiwithu.maicraft.task.SetTimerTool());
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.inventory.DropItemsTool());
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.inventory.TakeItemsTool());
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.interact.InspectGuiTool());
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.inventory.TransferTool());
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.interact.CloseGuiTool());
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.perception.GetSelfStatusTool());   // SAMPLE: raw MaiCraftTool
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.perception.GetOwnerStatusTool());
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.inventory.LookupRecipeTool());
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.inventory.CraftTool());
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.work.SemanticCookTool());
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.work.SemanticDimensionTravelTool());
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.work.SemanticStructureSearchTool());
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.work.SemanticDragonFightTool());
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.work.SemanticElytraTool());
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.work.SemanticMilestoneTool());
        ToolRegistry.register(new org.maiwithu.maicraft.core.task.trade.SemanticTradeTool());
        ToolRegistry.register(new org.maiwithu.maicraft.core.task.container.SemanticContainerTool());
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.perception.ScanNearbyEntitiesTool());
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.perception.ScanBlocksTool());
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.perception.ScaffoldMaterialsTool());
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.perception.LookAroundTool());
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.perception.InspectBlockTool());
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.perception.InspectBlockStorageTool());
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.perception.GetWorldInfoTool());
        // 部分功能把“工具 + 记录执行器”的注册封装在自己的 API 中，增加功能时先确认是否已成对注册。
        org.maiwithu.maicraft.core.tools.work.SemanticEntitySearchApi.register();
        org.maiwithu.maicraft.core.tools.work.SemanticExploreApi.register();
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.work.BoardStructureTool());
        ToolRegistry.register(new org.maiwithu.maicraft.core.tools.work.RegionalTravelTool());
        org.maiwithu.maicraft.core.tools.work.SemanticAcquireApi.register();
        org.maiwithu.maicraft.core.tools.work.SemanticLightAreaApi.register();
        org.maiwithu.maicraft.core.tools.work.SemanticBuildSiteInvestigationApi.register();
        org.maiwithu.maicraft.core.integration.create.CreateMechanicalPower.install();
        ToolRegistry.register(new org.maiwithu.maicraft.core.integration.create.CreateMechanicalPowerTool());
    }


    private static void registerTaskRunners() {
        // 按记录的具体 Java 类型查找执行器；仅注册工具却漏掉这里的对应关系，任务仍无法执行。
        TaskFactory.register(MoveToTaskRecord.class, (p, r) -> new MoveToCompanionTask(p, r));
        TaskFactory.register(org.maiwithu.maicraft.core.task.move.FollowTaskRecord.class,
                (p, r) -> new org.maiwithu.maicraft.core.task.move.FollowCompanionTask(p, r));
        TaskFactory.register(MineBlockTaskRecord.class, (p, r) -> new MineCompanionTask(p, r));
        TaskFactory.register(EquipTaskRecord.class, (p, r) -> new EquipCompanionTask(p, r));
        TaskFactory.register(org.maiwithu.maicraft.core.task.inventory.UnequipTaskRecord.class,
                (p, r) -> new org.maiwithu.maicraft.core.task.inventory.UnequipCompanionTask(p, r));
        TaskFactory.register(DropItemsTaskRecord.class, (p, r) -> new DropCompanionTask(p, r));
        TaskFactory.register(EatItemTaskRecord.class, (p, r) -> new EatCompanionTask(p, r));
        TaskFactory.register(AttackTaskRecord.class, (p, r) -> new AttackCompanionTask(p, r));
        TaskFactory.register(CollectItemsTaskRecord.class, (p, r) -> new CollectItemsCompanionTask(p, r));
        TaskFactory.register(FishTaskRecord.class, (p, r) -> new FishCompanionTask(p, r));
        TaskFactory.register(BuildTaskRecord.class, (p, r) -> new BuildCompanionTask(p, r));
        TaskFactory.register(InteractAtTaskRecord.class, (p, r) -> new InteractAtCompanionTask(p, r));
        TaskFactory.register(InteractEntityTaskRecord.class, (p, r) -> new InteractEntityCompanionTask(p, r));
        TaskFactory.register(LocateStructureTaskRecord.class, (p, r) -> new LocateStructureCompanionTask(p, r));
        TaskFactory.register(LocateBiomeTaskRecord.class, (p, r) -> new LocateBiomeCompanionTask(p, r));
        TaskFactory.register(org.maiwithu.maicraft.core.task.craft.CraftTaskRecord.class,
                (p, r) -> new org.maiwithu.maicraft.core.task.craft.CraftCompanionTask(p, r));
        TaskFactory.register(org.maiwithu.maicraft.core.task.menu.MenuSequenceTaskRecord.class,
                (p, r) -> new org.maiwithu.maicraft.core.task.menu.MenuSequenceCompanionTask(p, r));
        TaskFactory.register(org.maiwithu.maicraft.core.task.menu.CloseMenuTaskRecord.class,
                (p, r) -> new org.maiwithu.maicraft.core.task.menu.CloseMenuCompanionTask(p, r));
        TaskFactory.register(org.maiwithu.maicraft.core.task.container.ContainerTransferTaskRecord.class,
                (p, r) -> new org.maiwithu.maicraft.core.task.container.ContainerTransferCompanionTask(p, r));
        TaskFactory.register(org.maiwithu.maicraft.core.task.container.SemanticContainerTaskRecord.class,
                (p, r) -> new org.maiwithu.maicraft.core.task.container.SemanticContainerCompanionTask(p, r));
        TaskFactory.register(org.maiwithu.maicraft.core.task.inventory.SelectInventorySlotTaskRecord.class,
                (p, r) -> new org.maiwithu.maicraft.core.task.inventory.SelectInventorySlotCompanionTask(p, r));
        TaskFactory.register(org.maiwithu.maicraft.core.task.inventory.CreativeTakeItemsTaskRecord.class,
                (p, r) -> new org.maiwithu.maicraft.core.task.inventory.CreativeTakeItemsCompanionTask(p, r));
        TaskFactory.register(org.maiwithu.maicraft.core.task.sleep.SleepTaskRecord.class,
                (p, r) -> new org.maiwithu.maicraft.core.task.sleep.SleepCompanionTask(p, r));
        TaskFactory.register(
                org.maiwithu.maicraft.core.task.progression.ReachMilestoneTaskRecord.class,
                org.maiwithu.maicraft.core.task.progression.ReachMilestoneCompanionTask::new);
    }
}
