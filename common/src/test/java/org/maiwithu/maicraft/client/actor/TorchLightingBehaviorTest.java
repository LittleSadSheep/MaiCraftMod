// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.Direction;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.task.chain.TorchLightingChain;
import org.maiwithu.maicraft.core.task.lighting.RoutineTorchPlacement;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.IntentTaskRecord;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.Task;

/** 补光不能抢占精确工艺；灯与消耗分包到达时，只等原点击回执，不重复右键。 */
public final class TorchLightingBehaviorTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Method allowed = TorchLightingChain.class.getDeclaredMethod("ordinaryWork", TaskRecord.class); allowed.setAccessible(true);
        for (String ability : List.of("acquire_items", "travel", "find_block", "combat", "wait", "build_machine", "build", "light_area")) {
            var goal = new Goal("maicraft:" + ability, "fixture", null, "{}", "{}", List.of(), List.of());
            var task = new IntentTaskRecord(UUID.randomUUID(), null, goal);
            check((boolean) allowed.invoke(null, task) == Set.of("acquire_items", "travel", "find_block").contains(ability), "scope: " + ability);
        }
        try (var h = new InteractionWorldTestHarness()) {
            h.position(new Vec3(4.5, 1, 4.5)); h.inventory.setItem(0, new ItemStack(Items.TORCH, 4));
            var target = RoutineTorchPlacement.find(h.player, Set.of());
            var context = ClientRuntime.requireContext(h.player);
            var evidence = TorchLightingChain.confirmation(h.player, target);
            check(evidence.requiresBlockAcknowledgement(), "native server acknowledgement is required");
            var support = target.pos().below();
            var receipt = context.actions().useBlock(context, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(support), Direction.UP, support, false), evidence, 40);
            h.set(target.pos(), target.desiredState());
            h.nextTick(); context = ClientRuntime.requireContext(h.player);
            check(!context.actions().poll(context, receipt).terminal(), "predicted torch alone is not success");
            h.inventory.getItem(0).shrink(1); h.nextTick(); context = ClientRuntime.requireContext(h.player);
            check(!context.actions().poll(context, receipt).terminal(), "inventory prediction cannot replace server acknowledgement");
            h.level.acknowledgedSequence = h.level.blockSequence;
            h.nextTick(); context = ClientRuntime.requireContext(h.player);
            check(context.actions().poll(context, receipt).status() == NativeActionReceipt.Status.CONFIRMED_APPLIED,
                    "one authoritative torch and one consumed item confirm placement");
            check(h.blockUses() == 1, "waiting for packets submits only one click");
            h.set(target.pos(), Blocks.AIR.defaultBlockState());
            var rejected = TorchLightingChain.confirmation(h.player, target);
            check(rejected.observeAcknowledged(context) == NativeConfirmation.Verdict.NOT_APPLIED, "confirmed unchanged world is no placement");
            h.inventory.getItem(0).shrink(2);
            check(rejected.observe(context) == NativeConfirmation.Verdict.DIVERGED, "unexpected consumption is not hidden");
        }
        // 通过真实补光链准备主手、转头、出手，再在回执未到时被抢占；恢复只结算同一支灯。
        try (var h = new InteractionWorldTestHarness()) {
            h.position(new Vec3(4.5, 1, 4.5)); h.inventory.setItem(0, new ItemStack(Items.TORCH, 4));
            var target = RoutineTorchPlacement.find(h.player, Set.of());
            var chain = new TorchLightingChain();
            // Unsafe 身体夹具需补齐真实玩家构造器提供的干燥、尺寸和静止事实，才能运行站稳检查。
            ActorControlTestHarness.field(Entity.class, "fluidHeight").set(h.player, new Object2DoubleOpenHashMap<>());
            ActorControlTestHarness.field(Entity.class, "dimensions").set(h.player, EntityType.PLAYER.getDimensions());
            ActorControlTestHarness.field(Entity.class, "deltaMovement").set(h.player, Vec3.ZERO);
            h.player.inventoryMenu.setCarried(ItemStack.EMPTY);
            ActorControlTestHarness.field(TorchLightingChain.class, "candidate").set(chain, target);
            Vec3 direction = Vec3.atCenterOf(target.pos().below()).add(0, .5, 0).subtract(h.player.getEyePosition());
            h.player.setYRot((float) Math.toDegrees(Math.atan2(-direction.x, direction.z)));
            h.player.setXRot((float) -Math.toDegrees(Math.atan2(direction.y, Math.sqrt(direction.x * direction.x + direction.z * direction.z))));
            for (int i = 0; i < 8 && h.blockUses() == 0; i++) { chain.tick(h.player); h.nextTick(); }
            check(h.blockUses() == 1, "the routine itself submits one native torch use");
            chain.stop(h.player, Task.StopReason.PREEMPTED);
            h.position(new Vec3(8.5, 1, 4.5));
            h.set(target.pos(), target.desiredState()); h.inventory.getItem(0).shrink(1);
            h.level.acknowledgedSequence = h.level.blockSequence; h.nextTick(); chain.tick(h.player);
            check(h.blockUses() == 1 && !ActorControlTestHarness.field(TorchLightingChain.class, "active").getBoolean(chain),
                    "resuming after defense settles the old click and releases the body");
        }
        System.out.println("TorchLightingBehaviorTest: passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
