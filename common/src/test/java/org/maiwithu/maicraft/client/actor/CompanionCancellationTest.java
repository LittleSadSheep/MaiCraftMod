package org.maiwithu.maicraft.client.actor;

import java.lang.reflect.Field;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.mine.MineBlockTaskRecord;
import org.maiwithu.maicraft.core.task.mine.MineCompanionTask;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskState;
import sun.misc.Unsafe;

/** Cancellation on the client thread between actor ticks must reach nested navigation cleanup. */
public final class CompanionCancellationTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Field singleton = Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Unsafe memory = (Unsafe) singleton.get(null);
        Minecraft minecraft = (Minecraft) memory.allocateInstance(Minecraft.class);
        LocalPlayer player = (LocalPlayer) memory.allocateInstance(LocalPlayer.class);
        minecraft.player = player;
        minecraft.level = (ClientLevel) memory.allocateInstance(ClientLevel.class);
        field(Level.class, "dimension").set(minecraft.level, Level.OVERWORLD);
        field(LocalPlayer.class, "clientLevel").set(player, minecraft.level);
        field(LocalPlayer.class, "level").set(player, minecraft.level);
        field(Minecraft.class, "gameThread").set(minecraft, Thread.currentThread());

        ClientActorBoundary actor = ClientRuntime.actor();
        Field actorClient = field(ClientActorBoundary.class, "minecraft");
        Field globalClient = field(Minecraft.class, "instance");
        Object previousActorClient = actorClient.get(actor);
        Object previousGlobalClient = globalClient.get(null);
        try {
            actorClient.set(actor, minecraft);
            globalClient.set(null, minecraft);
            check(actor.activeContext().isEmpty(), "the simulated MCP cancellation has no actor tick lease");
            MineCompanionTask mine = new MineCompanionTask(player,
                    new MineBlockTaskRecord("cancel-test", 100, Set.of(), 1, "test"));
            PlayerNav route = PlayerNav.to(player, () -> null, 1.0D, () -> false);
            Field navigation = field(AbstractCompanionTask.class, "nav");
            navigation.set(mine, route);
            TestTask acquire = new TestTask(player, mine);
            TestTask supply = new TestTask(player, acquire);

            supply.stop(player, Task.StopReason.REPLACED);
            check(supply.result(TaskState.CANCELLED).interrupted(), "nested cancellation produces its normal receipt");
            check(supply.cleaned && acquire.cleaned, "parent cleanup completes after the real mining leaf");
            check(navigation.get(mine) == null, "the real mining task relinquishes its navigation reference");
            check(route.tick() == PlayerNav.Status.FAILED
                            && route.failReason().contains("navigation was stopped"),
                    "the retained route confirms stop ran rather than merely pausing its input");
        } finally {
            actorClient.set(actor, previousActorClient);
            globalClient.set(null, previousGlobalClient);
        }
        System.out.println("CompanionCancellationTest: passed");
    }

    private static final class TestTask extends AbstractCompanionTask<TaskRecord> {
        private final Task nested;
        boolean cleaned;

        TestTask(LocalPlayer player, Task nested) {
            super(player, null);
            this.nested = nested;
        }

        @Override protected TaskState onTick() { return TaskState.RUNNING; }
        @Override protected String successMessage() { return "complete"; }
        @Override protected void cleanup() {
            if (nested != null) {
                nested.stop(player, Task.StopReason.REPLACED);
                nested.result(TaskState.CANCELLED);
            }
            super.cleanup();
            cleaned = true;
        }
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
