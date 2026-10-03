package org.maiwithu.maicraft.client.actor;

import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.combat.Loadout;
import org.maiwithu.maicraft.core.task.combat.AttackCompanionTask;
import org.maiwithu.maicraft.core.task.combat.AttackTaskRecord;
import org.maiwithu.maicraft.task.TaskState;
import static org.maiwithu.maicraft.client.actor.CombatThreatsTest.check;

/** 用真实射击入口核对水平走位环与高差目标；持用计数不代表箭生成或命中。 */
public final class RangedDistanceBandTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        withinHorizontalBandStartsUse(8, 10, true);
        withinHorizontalBandStartsUse(8, 0, true);
        withinHorizontalBandStartsUse(13, 0, false);
        withinHorizontalBandStartsUse(8, 40, false);
        System.out.println("RangedDistanceBandTest: 高差目标、水平边界与三维弹道上限通过");
    }

    private static void withinHorizontalBandStartsUse(double horizontal, double height, boolean expected) throws Exception {
        // 同一水平站位分别放置平地和高台目标；目标高出十格时仍应进入弹道检查，超过真实射程则不能起手。
        try (var f = new CombatThreatsTest.Fixture()) {
            f.h.position(new Vec3(2.5, 1, 4.5));
            f.h.player.setDeltaMovement(Vec3.ZERO); // 静止射手具有真实的零速度，供原生弹道继承规则读取。
            ActorControlTestHarness.field(Level.class, "isClientSide").setBoolean(f.h.level, true);
            f.h.inventory.setItem(0, new ItemStack(Items.BOW));
            f.h.inventory.setItem(1, new ItemStack(Items.ARROW, 64));
            // 此夹具未载入原版箭标签，沿用既有射击回归的无限弹药条件；本用例只核对距离门控，不验证弹药消耗。
            f.h.player.getAbilities().instabuild = true;
            f.h.mode.itemUse = player -> player.startUsingItem(InteractionHand.MAIN_HAND);
            // 用完整边界封住夹具区块，让失败候选撞到真实方块，避免测试替身读取未初始化的远处地形。
            for (int a = 0; a < 16; a++) for (int b = 0; b < 16; b++) {
                f.h.set(new BlockPos(0, a, b), Blocks.STONE.defaultBlockState());
                f.h.set(new BlockPos(15, a, b), Blocks.STONE.defaultBlockState());
                f.h.set(new BlockPos(a, b, 0), Blocks.STONE.defaultBlockState());
                f.h.set(new BlockPos(a, b, 15), Blocks.STONE.defaultBlockState());
                f.h.set(new BlockPos(a, 15, b), Blocks.STONE.defaultBlockState());
            }
            var foe = f.mob(11, 2.5 + horizontal);
            Vec3 target = new Vec3(2.5 + horizontal, 1 + height, 4.5);
            ActorControlTestHarness.field(Entity.class, "position").set(foe, target);
            ActorControlTestHarness.field(Entity.class, "blockPosition").set(foe, BlockPos.containing(target));
            ActorControlTestHarness.field(Entity.class, "bb").set(foe,
                    new AABB(target.x - .3, target.y, target.z - .3, target.x + .3, target.y + 1.8, target.z + .3));
            var task = new AttackCompanionTask(f.h.player, new AttackTaskRecord("ranged-distance", 1000, List.of(11), false));
            task.start(f.h.player);
            ActorControlTestHarness.field(AttackCompanionTask.class, "target").set(task, foe);
            Method shoot = AttackCompanionTask.class.getDeclaredMethod("shootAt", Loadout.class);
            shoot.setAccessible(true);
            try {
                check(Loadout.forTarget(f.h.player, foe).ranged() != null, "距离回归的前提是正式选装确实能选择弓");
                // 只推进正式选装和射击阶段，不直接创建 RangedShot，也不替原生动作提供成功结论。
                for (int i = 0; i < 8 && f.h.mode.items == 0; i++) {
                    shoot.invoke(task, Loadout.forTarget(f.h.player, foe));
                    f.h.nextTick(); f.h.h.actions.advance(f.h.h.context);
                }
                check((f.h.mode.items == 1) == expected,
                        "水平距离=" + horizontal + "、高差=" + height + " 的实际弓持用次数=" + f.h.mode.items);
            } finally {
                f.h.nextTick(); task.result(TaskState.CANCELLED);
            }
        }
    }
}
