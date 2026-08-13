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
 * Loader-agnostic init for the {@code maicraft-core} tool pack — the worked example
 * of how the single Mod wires its internal semantic execution engine. Each client loader entry
 * point calls {@link #init()} once; one client-tick runtime advances scans, path snapshots and
 * the real local-player task body.
 *
 * <p>Two things plug into the engine here:
 * <ul>
 *   <li>tools — each a {@link org.maiwithu.maicraft.agent.tool.MaiCraftTool} (raw) and
 *       added to the global {@link ToolRegistry} (order preserved for prompt
 *       caching);</li>
 *   <li>task runners — each {@code TaskRecord} type a world-action tool emits is
 *       paired with the {@code CompanionTask} that runs it, via
 *       {@link CompanionTaskFactory#register}.</li>
 * </ul>
 */
public final class MaiCraftCore {

    private static boolean initialised = false;

    private MaiCraftCore() {}

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
     * 把 core 的五条生存本能链插进引擎的竞价调度(链登记口)。运输包与
     * 生命周期对接已随排程机器归引擎,不再是 core 的事。
     */
    private static void registerReflexes() {
        // 注册号小的先问 —— 与原版 addGoal(int priority, goal) 同一惯例。
        // 顺序<b>照搬旧的浮点优先级</b>(MLG 10 > 换气 6 > 自卫 5 > 进食 4/3 > 脱困 2),
        // 那些数值本身已经退役:反射之间的先后是固定的,不随世界状态变,用连续量
        // 表达一个固定序,数值就成了必须维护却没人看得懂的魔法数。
        //
        // 正在坠落是最迫近的死法,所以摔落缓冲压过一切;卡住只是烦人,绝不该压过
        // 打架或吃饭 —— 这条排序是有单测守着的(ReflexOrderTest)。
        org.maiwithu.maicraft.task.BrainChains.register(10,
                org.maiwithu.maicraft.core.task.chain.MLGChain::new);
        org.maiwithu.maicraft.task.BrainChains.register(20,
                org.maiwithu.maicraft.core.task.chain.BreathChain::new);
        org.maiwithu.maicraft.task.BrainChains.register(30,
                org.maiwithu.maicraft.core.task.chain.MobDefenseChain::new);
        org.maiwithu.maicraft.task.BrainChains.register(50,
                org.maiwithu.maicraft.core.task.chain.UnstuckChain::new);
    }

    /**
     * The reflex roster (constitution §6): enlist core's instincts — the five
     * survival chains and the pure policies. The switch persistence is bound by
     * the engine ({@code CommonClass.wireTaskMachine}). Runs on BOTH sides like
     * the rest of init.
     */
    private static void enlistReflexRoster() {
        org.maiwithu.maicraft.core.task.reflex.CoreReflexes.registerAll();
    }

    private static void registerTools() {

        // Registration ORDER is preserved (backends with prompt-caching keyed off
        // the tool list cache stably across requests).
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
        org.maiwithu.maicraft.core.tools.work.SemanticEntitySearchApi.register();
        org.maiwithu.maicraft.core.tools.work.SemanticExploreApi.register();
        org.maiwithu.maicraft.core.tools.work.SemanticAcquireApi.register();
        org.maiwithu.maicraft.core.tools.work.SemanticLightAreaApi.register();
        org.maiwithu.maicraft.core.tools.work.SemanticBuildSiteInvestigationApi.register();
        org.maiwithu.maicraft.core.integration.create.CreateMechanicalPower.install();
        ToolRegistry.register(new org.maiwithu.maicraft.core.integration.create.CreateMechanicalPowerTool());
    }


    private static void registerTaskRunners() {
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
