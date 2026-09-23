// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonParser;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.task.TaskState;
import java.io.File;
import net.minecraft.client.Minecraft;
import org.maiwithu.maicraft.client.server.ServerSessionRuntime;

/** 在通过准入后驱动真实启动转换，不打开服务器会话。 */
public final class ProductionStartupTransitionTest {
    public static void main(String[] args) throws Exception {
        verify("START",false); verify("START",true); verify("SUPPLY",false); verify("SUPPLY",true);
        System.out.println("ProductionStartupTransitionTest: first supply avoids empty startup admission; authored actions retain all gates");
    }

    private static void verify(String entryPhase, boolean startAction) throws Exception {
        var runtime = ServerSessionRuntime.class;
        var router = runtime.getDeclaredField("router"); router.setAccessible(true); Object priorRouter = router.get(null);
        var journal = runtime.getDeclaredField("journal"); journal.setAccessible(true); Object priorJournal = journal.get(null);
        try (var world = new InteractionWorldTestHarness()) {
            var directory = Minecraft.class.getDeclaredField("gameDirectory"); directory.setAccessible(true);
            directory.set(Minecraft.getInstance(),new File("production-startup-settings-fixture"));
            var manifest = ProductionObserverFixture.manifest(1);
            if (startAction) manifest.getAsJsonArray("configurations").add(JsonParser.parseString("""
                    {"id":"start_motor","node":"p0","operation":"machine.configure","stage":"start",
                     "arguments":{"action":"create.speed","value":16}}
                    """));
            var plan = new ProductionRunPlan(BlockPos.ZERO,"minecraft:overworld",manifest);
            var record = new MachineProductionTaskRecord("startup-transition",10_000,plan,null,List.of());
            var task = new MachineProductionTask(world.player,record,(stance,reached) -> {
                throw new AssertionError("A settled startup must not navigate");
            });
            var phase = MachineProductionTask.class.getDeclaredField("phase"); phase.setAccessible(true);
            for (Object value : phase.getType().getEnumConstants()) if (value.toString().equals(entryPhase)) phase.set(task,value);
            if (entryPhase.equals("SUPPLY")) {
                var supplied = MachineProductionTask.class.getDeclaredField("supply"); supplied.setAccessible(true);
                supplied.set(task,new ProductionInputSupply(world.player,record,plan,task,List.of()));
            }
            var index = MachineProductionTask.class.getDeclaredField("configuration"); index.setAccessible(true);
            // 手工指定动作在此处已经结算；转换仍必须使先前的准入扫描失效。
            index.setInt(task,plan.manifest().configurations().size());
            if (task.tick(world.player) != TaskState.RUNNING) throw new AssertionError("Startup unexpectedly terminated");
            String expected = startAction ? entryPhase.equals("SUPPLY") ? "ADMIT_START" : "REFRESH" : "OBSERVE";
            if (!phase.get(task).toString().equals(expected)) throw new AssertionError("Expected " + expected + " after startup");
        } finally { router.set(null,priorRouter); journal.set(null,priorJournal); }
    }
}
