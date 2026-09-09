package org.maiwithu.maicraft.core.task.inventory;
import org.maiwithu.maicraft.core.WorkProfile;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.FailureType;

import org.maiwithu.maicraft.task.TaskState;

import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.core.act.Interaction;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.base.Precondition;
import org.maiwithu.maicraft.core.task.FirstPersonActionGate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 真正把食物拿在手里，按住使用键，让游戏完成吃东西的动画、扣物品和增加饥饿值等效果。
 * 这里不直接改血量或饥饿值；结束后查看物品有没有减少，再判断是否吃成了。
 * 当前只支持有 FOOD 属性的食物，不支持所有能喝的物品。
 */
public final class EatCompanionTask extends AbstractCompanionTask<EatItemTaskRecord> {

    private Interaction eat;
    private final FirstPersonActionGate selection = new FirstPersonActionGate();
    private int foodSlot;
    private int beforeCount;
    private float beforeHp;
    private int beforeFood;
    private String doneMessage = "done";

    public EatCompanionTask(LocalPlayer player, EatItemTaskRecord record) {
        super(player, record);
    }

    @Override
    protected List<Precondition> preconditions() {
        // 先查当前模式是否按普通饥饿规则运行，再查有没有这种物品、这种物品默认是否有食物属性。
        return List.of(
                // 当前用“食物数量减少”确认成功；创造模式不会正常扣物品，所以在开始前直接拒绝。
                () -> WorkProfile.of(player).hasHunger() ? null
                        : new Precondition.Failure(
                                "creative mode has no hunger — eating is unnecessary; kept the "
                                        + r.label, FailureType.UNKNOWN),
                () -> PlayerInv.count(player.getInventory(), r.item) > 0 ? null
                        : new Precondition.Failure("no " + r.label + " in inventory to eat",
                                FailureType.NO_MATERIAL),
                // 检查的是这个物品新建一叠时的默认 FOOD 属性，不是背包里那一叠可能被修改过的属性。
                () -> new ItemStack(r.item).get(DataComponents.FOOD) != null ? null
                        : new Precondition.Failure(r.label + " can't be eaten or drunk",
                                FailureType.UNKNOWN));
    }

    @Override
    protected void onStart() {
        // 先记住吃之前的物品数量、血量和饥饿值，最后才能说明发生了什么变化。
        beforeCount = PlayerInv.count(player.getInventory(), r.item);
        beforeHp = player.getHealth();
        beforeFood = player.getFoodData().getFoodLevel();
        // 找到要拿的食物位置；接下来能不能开始吃，由游戏本身处理，例如普通食物在吃饱时不会开吃。
        foodSlot = PlayerInv.findSlot(player.getInventory(), r.item);
    }

    @Override
    protected TaskState onTick() {
        if (eat == null) {
            // 先等食物真的切换到手上，失败就停；成功后只创建一次持续使用动作，之后接着等待它。
            FirstPersonActionGate.Status selected = selection.select(player, foodSlot);
            if (selected == FirstPersonActionGate.Status.RUNNING) return TaskState.RUNNING;
            if (selected == FirstPersonActionGate.Status.FAILED) {
                fail("couldn't select " + r.label + ": " + selection.failure(), FailureType.UNKNOWN);
                return TaskState.FAILED;
            }
            eat = Interaction.useInAir(player, InteractionHand.MAIN_HAND, Interaction.Timing.hold());
        }
        return switch (eat.tick()) {
            case DONE -> finish();
            case FAILED -> {
                fail("couldn't eat " + r.label + ": " + eat.failReason(), FailureType.UNKNOWN);
                yield TaskState.FAILED;
            }
            case RUNNING -> TaskState.RUNNING;
        };
    }

    /** 使用动作结束后，用同类型物品数量是否减少来判断有没有吃掉，增加的血量和饥饿值只是附加说明。 */
    private TaskState finish() {
        int now = PlayerInv.count(player.getInventory(), r.item);
        if (now >= beforeCount) {
            // 没看到数量减少就报告失败；当前提示统一说“已经吃饱”，这里没有进一步证明失败原因。
            fail("didn't eat " + r.label + " — already full (hunger " + beforeFood + "/20). Kept it.",
                    FailureType.UNKNOWN);
            return TaskState.FAILED;
        }
        int foodGain = player.getFoodData().getFoodLevel() - beforeFood;
        float healed = player.getHealth() - beforeHp;
        doneMessage = "ate " + r.label + " — hunger " + player.getFoodData().getFoodLevel() + "/20"
                + (foodGain > 0 ? " (+" + foodGain + ")" : "")
                + (healed > 0.0f ? ", HP " + fmt(player.getHealth()) + " (+" + fmt(healed) + ")" : "");
        return TaskState.SUCCESS;
    }

    private static String fmt(float v) {
        return v == Math.floor(v) ? String.valueOf((int) v) : String.format("%.1f", v);
    }

    /** 停止拿取等待和持续使用动作，避免任务结束后仍一直按住使用键。 */
    @Override
    protected void cleanup() {
        selection.reset();
        if (eat != null) {
            eat.stop();
        }
    }

    @Override
    protected Map<String, Object> resultData() {
        // 无论成功还是失败，附上此刻的血量和饥饿值，方便调用者看当前身体状态。
        Map<String, Object> data = new HashMap<>();
        data.put("item", r.label);
        data.put("hp", player.getHealth());
        data.put("hunger", player.getFoodData().getFoodLevel());
        return data;
    }

    @Override
    protected String successMessage() {
        return doneMessage;
    }

    @Override
    protected String timeoutMessage() {
        return "couldn't finish eating " + r.label;
    }

    @Override
    protected String cancelledMessage() {
        return "eating " + r.label + " interrupted — no effect";
    }
}
