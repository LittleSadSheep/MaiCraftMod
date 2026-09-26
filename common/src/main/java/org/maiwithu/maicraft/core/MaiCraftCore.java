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
import org.maiwithu.maicraft.core.integration.create.CreateMechanicalPower;
import org.maiwithu.maicraft.core.integration.create.CreateMechanicalPowerTool;
import org.maiwithu.maicraft.core.task.chain.BreathChain;
import org.maiwithu.maicraft.core.task.chain.MLGChain;
import org.maiwithu.maicraft.core.task.chain.MobDefenseChain;
import org.maiwithu.maicraft.core.task.container.ContainerTransferCompanionTask;
import org.maiwithu.maicraft.core.task.container.ContainerTransferTaskRecord;
import org.maiwithu.maicraft.core.task.container.SemanticContainerCompanionTask;
import org.maiwithu.maicraft.core.task.container.SemanticContainerTaskRecord;
import org.maiwithu.maicraft.core.task.container.SemanticContainerTool;
import org.maiwithu.maicraft.core.task.craft.CraftCompanionTask;
import org.maiwithu.maicraft.core.task.craft.CraftTaskRecord;
import org.maiwithu.maicraft.core.task.enchant.EnchantTool;
import org.maiwithu.maicraft.core.task.inventory.CreativeTakeItemsCompanionTask;
import org.maiwithu.maicraft.core.task.inventory.CreativeTakeItemsTaskRecord;
import org.maiwithu.maicraft.core.task.inventory.SelectInventorySlotCompanionTask;
import org.maiwithu.maicraft.core.task.inventory.SelectInventorySlotTaskRecord;
import org.maiwithu.maicraft.core.task.inventory.UnequipCompanionTask;
import org.maiwithu.maicraft.core.task.inventory.UnequipTaskRecord;
import org.maiwithu.maicraft.core.task.menu.CloseMenuCompanionTask;
import org.maiwithu.maicraft.core.task.menu.CloseMenuTaskRecord;
import org.maiwithu.maicraft.core.task.menu.MenuSequenceCompanionTask;
import org.maiwithu.maicraft.core.task.menu.MenuSequenceTaskRecord;
import org.maiwithu.maicraft.core.task.move.FollowCompanionTask;
import org.maiwithu.maicraft.core.task.move.FollowTaskRecord;
import org.maiwithu.maicraft.core.task.progression.ReachMilestoneCompanionTask;
import org.maiwithu.maicraft.core.task.progression.ReachMilestoneTaskRecord;
import org.maiwithu.maicraft.core.task.reflex.CoreReflexes;
import org.maiwithu.maicraft.core.task.sleep.SleepCompanionTask;
import org.maiwithu.maicraft.core.task.sleep.SleepTaskRecord;
import org.maiwithu.maicraft.core.task.trade.SemanticTradeTool;
import org.maiwithu.maicraft.core.tools.interact.CloseGuiTool;
import org.maiwithu.maicraft.core.tools.interact.InspectGuiTool;
import org.maiwithu.maicraft.core.tools.interact.InteractAtTool;
import org.maiwithu.maicraft.core.tools.interact.InteractEntityTool;
import org.maiwithu.maicraft.core.tools.interact.SleepTool;
import org.maiwithu.maicraft.core.tools.inventory.CraftTool;
import org.maiwithu.maicraft.core.tools.inventory.DropItemsTool;
import org.maiwithu.maicraft.core.tools.inventory.EatItemTool;
import org.maiwithu.maicraft.core.tools.inventory.EquipItemTool;
import org.maiwithu.maicraft.core.tools.inventory.LookupRecipeTool;
import org.maiwithu.maicraft.core.tools.inventory.TakeItemsTool;
import org.maiwithu.maicraft.core.tools.inventory.TransferTool;
import org.maiwithu.maicraft.core.tools.locate.LocateBiomeTool;
import org.maiwithu.maicraft.core.tools.locate.LocateStructureTool;
import org.maiwithu.maicraft.core.tools.perception.BlueprintReadTool;
import org.maiwithu.maicraft.core.tools.perception.GetOwnerStatusTool;
import org.maiwithu.maicraft.core.tools.perception.GetSelfStatusTool;
import org.maiwithu.maicraft.core.tools.perception.GetWorldInfoTool;
import org.maiwithu.maicraft.core.tools.perception.InspectBlockStorageTool;
import org.maiwithu.maicraft.core.tools.perception.InspectBlockTool;
import org.maiwithu.maicraft.core.tools.perception.LookAroundTool;
import org.maiwithu.maicraft.core.tools.perception.ScaffoldMaterialsTool;
import org.maiwithu.maicraft.core.tools.perception.ScanBlocksTool;
import org.maiwithu.maicraft.core.tools.perception.ScanNearbyEntitiesTool;
import org.maiwithu.maicraft.core.tools.work.AttackTool;
import org.maiwithu.maicraft.core.tools.work.AutoMineTool;
import org.maiwithu.maicraft.core.tools.work.BlueprintTool;
import org.maiwithu.maicraft.core.tools.work.BoardStructureTool;
import org.maiwithu.maicraft.core.tools.work.BuildTool;
import org.maiwithu.maicraft.core.tools.work.CollectItemsTool;
import org.maiwithu.maicraft.core.tools.work.FishTool;
import org.maiwithu.maicraft.core.tools.work.FollowTool;
import org.maiwithu.maicraft.core.tools.work.MoveToTool;
import org.maiwithu.maicraft.core.tools.work.RegionalTravelTool;
import org.maiwithu.maicraft.core.tools.work.SemanticAcquireApi;
import org.maiwithu.maicraft.core.tools.work.SemanticCookTool;
import org.maiwithu.maicraft.core.tools.work.SemanticDimensionTravelTool;
import org.maiwithu.maicraft.core.tools.work.SemanticDragonFightTool;
import org.maiwithu.maicraft.core.tools.work.SemanticElytraTool;
import org.maiwithu.maicraft.core.tools.work.SemanticEntitySearchApi;
import org.maiwithu.maicraft.core.tools.work.SemanticExploreApi;
import org.maiwithu.maicraft.core.tools.work.SemanticLightAreaApi;
import org.maiwithu.maicraft.core.tools.work.SemanticMilestoneTool;
import org.maiwithu.maicraft.core.tools.work.SemanticStructureSearchTool;
import org.maiwithu.maicraft.task.BrainChains;
import org.maiwithu.maicraft.task.SetTimerTool;
import org.maiwithu.maicraft.task.TaskStatusTool;
import org.maiwithu.maicraft.task.TaskStopTool;

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
        // 数字越小越先检查：入水低氧时先换气，尚在坠落时由落地救援接管，之后才考虑自卫。
        //
        // 卡住后的绕路、挖路或垫脚交给当前寻路任务处理：它知道这次允不允许改地形、哪些格不能碰。
        // 这里不另加一条见到卡住就抢身体乱走的自救行为，以免打断本来正在完成的挖掘或跳跃。
        BrainChains.register(10,
                MLGChain::new);
        // 已经入水且缺氧时，换气优先于水桶落地后的回收收尾；仍在空中坠落时换气链不会触发。
        BrainChains.register(5,
                BreathChain::new);
        BrainChains.register(30,
                MobDefenseChain::new);
    }

    /**
     * 登记反射的名称和说明。这里的实例只供查询，实际执行实例由上面的工厂创建。
     */
    private static void enlistReflexRoster() {
        CoreReflexes.registerAll();
    }

    private static void registerTools() {

        // 工具负责接收参数、生成任务记录或即时结果；需要持续操作身体的功能还必须注册执行器。
        ToolRegistry.register(new MoveToTool());
        ToolRegistry.register(new AttackTool());
        ToolRegistry.register(new LocateStructureTool());
        ToolRegistry.register(new LocateBiomeTool());
        ToolRegistry.register(new CollectItemsTool());
        ToolRegistry.register(new FishTool());
        ToolRegistry.register(new FollowTool());
        ToolRegistry.register(new AutoMineTool());
        ToolRegistry.register(new EquipItemTool());
        ToolRegistry.register(new BuildTool());
        ToolRegistry.register(new BlueprintTool());
        ToolRegistry.register(new BlueprintReadTool());
        ToolRegistry.register(new InteractAtTool());
        ToolRegistry.register(new SleepTool());
        ToolRegistry.register(new InteractEntityTool());
        ToolRegistry.register(new EatItemTool());
        ToolRegistry.register(new TaskStatusTool());
        ToolRegistry.register(new TaskStopTool());
        ToolRegistry.register(new SetTimerTool());
        ToolRegistry.register(new DropItemsTool());
        ToolRegistry.register(new TakeItemsTool());
        ToolRegistry.register(new InspectGuiTool());
        ToolRegistry.register(new TransferTool());
        ToolRegistry.register(new CloseGuiTool());
        ToolRegistry.register(new GetSelfStatusTool());
        ToolRegistry.register(new GetOwnerStatusTool());
        ToolRegistry.register(new LookupRecipeTool());
        ToolRegistry.register(new CraftTool());
        ToolRegistry.register(new SemanticCookTool());
        // 附魔沿用已有台子和可见菜单，只登记单次有预算的原生消费，不提供裸按钮或经验修改接口。
        ToolRegistry.register(new EnchantTool());
        ToolRegistry.register(new SemanticDimensionTravelTool());
        ToolRegistry.register(new SemanticStructureSearchTool());
        ToolRegistry.register(new SemanticDragonFightTool());
        ToolRegistry.register(new SemanticElytraTool());
        ToolRegistry.register(new SemanticMilestoneTool());
        ToolRegistry.register(new SemanticTradeTool());
        ToolRegistry.register(new SemanticContainerTool());
        ToolRegistry.register(new ScanNearbyEntitiesTool());
        ToolRegistry.register(new ScanBlocksTool());
        ToolRegistry.register(new ScaffoldMaterialsTool());
        ToolRegistry.register(new LookAroundTool());
        ToolRegistry.register(new InspectBlockTool());
        ToolRegistry.register(new InspectBlockStorageTool());
        ToolRegistry.register(new GetWorldInfoTool());
        // 部分功能把“工具 + 记录执行器”的注册封装在自己的 API 中，增加功能时先确认是否已成对注册。
        SemanticEntitySearchApi.register();
        SemanticExploreApi.register();
        ToolRegistry.register(new BoardStructureTool());
        ToolRegistry.register(new RegionalTravelTool());
        SemanticAcquireApi.register();
        SemanticLightAreaApi.register();
        CreateMechanicalPower.install();
        ToolRegistry.register(new CreateMechanicalPowerTool());
    }


    private static void registerTaskRunners() {
        // 按记录的具体 Java 类型查找执行器；仅注册工具却漏掉这里的对应关系，任务仍无法执行。
        TaskFactory.register(MoveToTaskRecord.class, (p, r) -> new MoveToCompanionTask(p, r));
        TaskFactory.register(FollowTaskRecord.class,
                (p, r) -> new FollowCompanionTask(p, r));
        TaskFactory.register(MineBlockTaskRecord.class, (p, r) -> new MineCompanionTask(p, r));
        TaskFactory.register(EquipTaskRecord.class, (p, r) -> new EquipCompanionTask(p, r));
        TaskFactory.register(UnequipTaskRecord.class,
                (p, r) -> new UnequipCompanionTask(p, r));
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
        TaskFactory.register(CraftTaskRecord.class,
                (p, r) -> new CraftCompanionTask(p, r));
        TaskFactory.register(MenuSequenceTaskRecord.class,
                (p, r) -> new MenuSequenceCompanionTask(p, r));
        TaskFactory.register(CloseMenuTaskRecord.class,
                (p, r) -> new CloseMenuCompanionTask(p, r));
        TaskFactory.register(ContainerTransferTaskRecord.class,
                (p, r) -> new ContainerTransferCompanionTask(p, r));
        TaskFactory.register(SemanticContainerTaskRecord.class,
                (p, r) -> new SemanticContainerCompanionTask(p, r));
        TaskFactory.register(SelectInventorySlotTaskRecord.class,
                (p, r) -> new SelectInventorySlotCompanionTask(p, r));
        TaskFactory.register(CreativeTakeItemsTaskRecord.class,
                (p, r) -> new CreativeTakeItemsCompanionTask(p, r));
        TaskFactory.register(SleepTaskRecord.class,
                (p, r) -> new SleepCompanionTask(p, r));
        TaskFactory.register(
                ReachMilestoneTaskRecord.class,
                ReachMilestoneCompanionTask::new);
    }
}
