package org.maiwithu.maicraft.client.actor;

import com.google.gson.JsonParser;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.phys.EntityHitResult;
import org.maiwithu.maicraft.core.act.Interaction;
import org.maiwithu.maicraft.core.task.MouseButton;
import org.maiwithu.maicraft.core.task.entity.SheepTraits;
import org.maiwithu.maicraft.core.task.interact.InteractEntityCompanionTask;
import org.maiwithu.maicraft.core.task.interact.InteractEntityTaskRecord;
import org.maiwithu.maicraft.task.TaskState;
import static org.maiwithu.maicraft.client.actor.SheepTraitsTest.check;

/** 剪毛前变色应停手，剪毛动作自己的状态变化则应正常收尾，不能重新点击。 */
public final class SheepInteractionTest {
    public static void main(String[] args) throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var sheep = SheepTraitsTest.sheep(world, 71, DyeColor.BLACK, 3);
            var record = new InteractEntityTaskRecord("white-use", 1000, MouseButton.RIGHT, 71, 0, null)
                    .withSheepTraits(SheepTraits.read(JsonParser.parseString("{sheep_color:'white',sheep_sheared:false}").getAsJsonObject()));
            var task = new InteractEntityCompanionTask(world.player, record);
            ActorControlTestHarness.field(InteractEntityCompanionTask.class, "entity").set(task, sheep);
            var act = InteractEntityCompanionTask.class.getDeclaredMethod("act"); act.setAccessible(true);
            check(act.invoke(task) == TaskState.FAILED, "recolored sheep is rejected before interaction");
            // 模拟一次已确认的剪毛点击；状态同步为已剪毛后，只结束该点击，不再发送原生使用。
            sheep.color = DyeColor.WHITE; sheep.shorn = true;
            task = new InteractEntityCompanionTask(world.player, record);
            ActorControlTestHarness.field(InteractEntityCompanionTask.class, "entity").set(task, sheep);
            var interaction = Interaction.forHit(world.player, new EntityHitResult(sheep), Interaction.Button.USE, 0, true);
            ActorControlTestHarness.field(Interaction.class, "fires").setInt(interaction, 1);
            ActorControlTestHarness.field(InteractEntityCompanionTask.class, "interaction").set(task, interaction);
            check(act.invoke(task) == TaskState.SUCCESS && interaction.confirmedUses() == 1,
                    "the successful shear is retained without a second use after its own trait change");
        }
        System.out.println("SheepInteractionTest: passed");
    }
}
