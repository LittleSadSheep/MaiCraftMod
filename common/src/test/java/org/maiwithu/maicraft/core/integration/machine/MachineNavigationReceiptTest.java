package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy;
import org.maiwithu.maicraft.task.TaskState;

/** 工地加载失败必须保留真实导航终态，不能让模型把一次防卫中断误当成蓝图或工地坐标错误。 */
public final class MachineNavigationReceiptTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var world = new InteractionWorldTestHarness()) {
            var blueprint = JsonParser.parseString("{\"schema_version\":1,\"blocks\":[{\"block_id\":\"minecraft:stone\",\"offset\":[0,0,0]}]}").getAsJsonObject();
            var target = new BlockPos(500, 1, 3);
            var plan = MachineConstructionPlan.compile(target, MachineBlueprintDocument.compile(blueprint, MachineConstructionPlan.registry()), false);
            var record = new MachineBuildTaskRecord("navigation-receipt", 1000, plan, "minecraft:overworld", MaterialPolicy.INVENTORY_ONLY, List.of());
            var task = new MachineBuildTask(world.player, record);
            var nav = PlayerNav.toGoal(world.player, () -> NavGoal.column(500, 3), 1, () -> false);
            var transport = field(PlayerNav.class, "navigator").get(nav); var ground = field(transport.getClass(), "ground").get(transport);
            var interrupt = ground.getClass().getDeclaredMethod("preempted", String.class); interrupt.setAccessible(true);
            interrupt.invoke(ground, "fixture navigation owner was interrupted");
            field(MachineBuildTask.class, "nav").set(task, nav);
            var load = MachineBuildTask.class.getDeclaredMethod("load", BlockPos.class); load.setAccessible(true);
            check(load.invoke(task, target) == TaskState.FAILED, "failed native route settles the machine survey");
            var result = task.result(TaskState.FAILED);
            var evidence = (Map<?, ?>) result.data().get("navigation_failure");
            check("interrupted".equals(result.data().get("failure_type")) && "interrupted".equals(evidence.get("failure_type"))
                    && result.message().contains("fixture navigation owner was interrupted"), "wrapper preserves concrete failure type and reason");
            check(evidence.get("target").equals(Map.of("x", 500, "y", 1, "z", 3))
                    && Boolean.FALSE.equals(result.data().get("construction_complete")), "remote target evidence cannot imply any completed construction");
        }
        System.out.println("MachineNavigationReceiptTest: passed");
    }
    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            try { var field = owner.getDeclaredField(name); field.setAccessible(true); return field; } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void check(boolean value, String detail) { if (!value) throw new AssertionError(detail); }
}
