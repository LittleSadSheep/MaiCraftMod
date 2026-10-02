package org.maiwithu.maicraft.client.actor;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.combat.Loadout;
import org.maiwithu.maicraft.core.combat.PvpTactics;
import org.maiwithu.maicraft.core.combat.Menace;
import org.maiwithu.maicraft.core.task.combat.AttackCompanionTask;
import org.maiwithu.maicraft.core.task.combat.AttackTaskRecord;
import org.maiwithu.maicraft.task.TaskState;
import static org.maiwithu.maicraft.client.actor.CombatThreatsTest.check;

/** 用实际玩家碰撞箱和充能状态验证战术，不把“到了某一格”冒充已经能够命中。 */
public final class PvpTacticsTest {
    public static void main(String[] args) throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var other = PvpTestPlayers.create(f, 21, 4);
            var task = new AttackCompanionTask(f.h.player, new AttackTaskRecord("tactics", 1000, List.of(21), false)); task.start(f.h.player);
            // 充能好时有可进入的攻击环；冷却与受击保护期间退到对手近战范围之外。
            var ready = PvpTactics.band(f.h.player, other, false);
            ActorControlTestHarness.field(Player.class, "attackStrengthTicker").setInt(f.h.player, 0);
            var waiting = PvpTactics.band(f.h.player, other, false);
            check(ready.outer() < waiting.inner() && waiting.inner() > Menace.strikeRangeOf(other, f.h.player), "冷却时拉开而不是贴身等待");
            ActorControlTestHarness.field(Player.class, "attackStrengthTicker").setInt(f.h.player, 100); other.hurtTime = 8;
            check(PvpTactics.band(f.h.player, other, false).inner() == waiting.inner(), "等待对手受击保护结束再接敌");
            other.hurtTime = 0;
            check(PvpTactics.stance(f.h.player, other, false, List.of(other)).isAt(new BlockPos(1, 1, 3)),
                    "对手危险范围不能否决自己的攻击距离环");
            // 同一距离上保留已有武器状态，避免追击过程中每刻换弓和剑。
            check(PvpTactics.ranged(8, true, true, false) && PvpTactics.ranged(6, true, true, true)
                    && !PvpTactics.ranged(6, true, true, false) && !PvpTactics.ranged(4, true, true, true), "远近切换有滞回区间");
            var quiet = PvpTactics.band(f.h.player, other, true); other.setDeltaMovement(new Vec3(-.4, 0, .2));
            check(PvpTactics.band(f.h.player, other, true).inner() > quiet.inner(), "对手快速逼近时扩大拉弓空间");
            other.setDeltaMovement(new Vec3(10, 10, -10));
            check(other.getBoundingBox().contains(PvpTactics.aimPoint(other)), "提前瞄点不能飞出真实碰撞箱");
            // 举盾时即使剑面板伤害更高，也选择可执行槽位中的斧进行原版破盾尝试。
            f.h.inventory.setItem(0, new ItemStack(Items.NETHERITE_SWORD)); f.h.inventory.setItem(1, new ItemStack(Items.WOODEN_AXE));
            check(Loadout.forTarget(f.h.player, other).melee().slot() == 0, "未举盾时保留正常武器评分");
            other.blocking = true;
            check(Loadout.forTarget(f.h.player, other).melee().slot() == 1, "举盾时选择斧");
            task.result(TaskState.CANCELLED);
        }
        System.out.println("PvpTacticsTest: 冷却距离、远近切换、碰撞箱瞄准和破盾选装通过");
    }
}
