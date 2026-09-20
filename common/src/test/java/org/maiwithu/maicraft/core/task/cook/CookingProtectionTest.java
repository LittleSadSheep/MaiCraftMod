// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.cook;

import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord.Source;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.task.TaskState;

/** 不经过 IntentTask 外壳，直接启动内部烹饪也必须跳过受保护炉子，并拒绝失效的保护名字。 */
public final class CookingProtectionTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var field = IntentRuntime.class.getDeclaredField("landmarks"); field.setAccessible(true);
        @SuppressWarnings("unchecked") var landmarks = (Map<String, IntentRuntime.Landmark>) field.get(IntentRuntime.get());
        String label = "cook-test-protected";
        var previous = landmarks.put(label, new IntentRuntime.Landmark(label, new Goal.WorldPosition(1, 1, 3, "minecraft:overworld")));
        try (var world = new CookingTestWorld()) {
            world.inventory(0, 16, 2); BlockPos other = new BlockPos(3, 1, 3); world.game.set(other, Blocks.FURNACE.defaultBlockState());
            var task = new SemanticCookCompanionTask(world.game.player, request(label));
            task.start(world.game.player); task.tick(world.game.player); task.tick(world.game.player);
            check(other.equals(CookingTestWorld.read(task, "stationPos")), "保护名单应约束烹饪本体的选炉，不只约束获取材料");
            landmarks.remove(label);
            var unresolved = new SemanticCookCompanionTask(world.game.player, request(label));
            unresolved.start(world.game.player);
            check(unresolved.tick(world.game.player) == TaskState.FAILED
                    && "unresolved_protected_label".equals(CookingTestWorld.read(unresolved, "failureCode")), "不存在的保护标签不能被解释成没有保护");
            check(world.game.blockUses() == 0 && world.game.itemUses() == 0, "保护检查本身不启动物品操作");
        } finally {
            if (previous == null) landmarks.remove(label); else landmarks.put(label, previous);
        }
        System.out.println("CookingProtectionTest: passed");
    }
    private static SemanticCookTaskRecord request(String label) {
        return new SemanticCookTaskRecord("protected-cook", 1000, CookingTestWorld.id("iron_ingot"), 16,
                SemanticCookTaskRecord.Preference.AUTO, List.of(), List.of(Source.INVENTORY), false, List.of(label));
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
