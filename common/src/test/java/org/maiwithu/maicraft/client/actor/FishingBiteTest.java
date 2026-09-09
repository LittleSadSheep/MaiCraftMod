// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.SharedConstants;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.core.mixin.FishingHookAccessor;
import org.maiwithu.maicraft.core.task.fish.FishCompanionTask;
import org.maiwithu.maicraft.core.task.fish.FishTaskRecord;
import org.maiwithu.maicraft.task.TaskState;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 使用真实鱼钩的同步数据回调，验证任务何时收竿；独立 JVM 中按 Mixin 声明绑定访问器。 */
public final class FishingBiteTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        observesClientBite(false);
        observesClientBite(true);
        System.out.println("FishingBiteTest: synchronized bites reel once without server nibble ticks");
    }

    private static void observesClientBite(boolean withdrawnSignal) throws Exception {
        try (var f = new InteractionWorldTestHarness()) {
            ActorControlTestHarness.field(Level.class, "isClientSide").setBoolean(f.level, true);
            f.inventory.setItem(0, new ItemStack(Items.FISHING_ROD));
            var hook = new ClientHook(f.level);
            f.player.fishing = hook;
            var record = new FishTaskRecord("fish", 2000, 1);
            var task = new FishCompanionTask(f.player, record);
            Field phase = ActorControlTestHarness.field(FishCompanionTask.class, "phase");
            for (Object value : phase.getType().getEnumConstants()) {
                if (value.toString().equals("WAIT")) phase.set(task, value);
            }
            Method wait = FishCompanionTask.class.getDeclaredMethod("waitForBite");
            wait.setAccessible(true);
            @SuppressWarnings("unchecked")
            var biting = (EntityDataAccessor<Boolean>) ActorControlTestHarness
                    .field(FishingHook.class, "DATA_BITING").get(null);
            Field nibble = ActorControlTestHarness.field(FishingHook.class, "nibble");

            check(wait.invoke(task) == TaskState.RUNNING && f.itemUses() == 0,
                    "waiting without a bite must leave the hook in the water");
            hook.getEntityData().set(biting, true);
            check(ActorControlTestHarness.field(FishingHook.class, "biting").getBoolean(hook)
                            && nibble.getInt(hook) == 0,
                    "the real client synchronization must update biting without updating nibble");

            if (withdrawnSignal) {
                hook.getEntityData().set(biting, false);
                nibble.setInt(hook, 20);
                check(wait.invoke(task) == TaskState.RUNNING && f.itemUses() == 0
                                && phase.get(task).toString().equals("WAIT"),
                        "a withdrawn client bite cannot be revived by a server-only countdown");
                nibble.setInt(hook, 0);
                hook.getEntityData().set(biting, true);
            }

            check(wait.invoke(task) == TaskState.RUNNING && f.itemUses() == 1
                            && phase.get(task).toString().equals("COLLECT"),
                    "a synchronized bite must immediately submit one reel and enter loot collection");
            check(record.caught() == 0, "a bite alone cannot claim successful loot receipt");
        }
    }

    private static final class ClientHook extends FishingHook implements FishingHookAccessor {
        ClientHook(Level level) { super(EntityType.FISHING_BOBBER, level); }

        @Override public boolean maicraft$isBiting() {
            try {
                String target = FishingHookAccessor.class.getMethod("maicraft$isBiting")
                        .getAnnotation(Accessor.class).value();
                return ActorControlTestHarness.field(FishingHook.class, target).getBoolean(this);
            } catch (Exception failure) {
                throw new AssertionError("the declared fishing accessor must bind to a vanilla field", failure);
            }
        }
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
