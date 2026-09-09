package org.maiwithu.maicraft.core.task.build;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.task.TaskState;

/** Drive real construction cell completion: retained materials are not cleaned after every block. */
public final class CreativeBuildRetentionTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            h.player.getAbilities().instabuild = true;
            h.position(new Vec3(3.12, 1, 4.8));
            var stack = new ItemStack(Items.OAK_PLANKS);
            h.inventory.setItem(0, stack.copy());
            h.inventory.setItem(1, new ItemStack(Items.DIAMOND, 64));
            var a = new BuildTaskRecord.Target(Blocks.OAK_PLANKS, Items.OAK_PLANKS,
                    new BlockPos(4, 1, 4), "first", null, null, null);
            var b = new BuildTaskRecord.Target(Blocks.OAK_PLANKS, Items.OAK_PLANKS,
                    new BlockPos(5, 1, 4), "second", null, null, null);
            var record = new BuildTaskRecord("creative-cache", 1000, List.of(a, b), false, false);
            record.previewManaged(true);
            var task = new FirstPersonBuildCompanionTask(h.player, record);
            task.onStart();
            var supply = (CreativeBuildMaterialSupply) field(task.getClass(), "creativeMaterials").get(task);
            var cache = (CreativeBuildInventory) field(supply.getClass(), "inventory").get(supply);
            check(cache.confirmedCreated(0, stack, CreativeBuildMaterialSupply.snapshot(h.player)), "record confirmed creation");
            Class<?> cellType = Class.forName(task.getClass().getName() + "$CellPlan");
            Constructor<?> constructor = cellType.getDeclaredConstructor(BuildTaskRecord.Target.class, List.class);
            constructor.setAccessible(true);
            Object first = constructor.newInstance(a, List.of()), second = constructor.newInstance(b, List.of());
            field(task.getClass(), "queue").set(task, new ArrayList<>(List.of(first, second)));

            for (int index = 0; index < 2; index++) {
                var target = index == 0 ? a : b;
                field(task.getClass(), "cell").set(task, index == 0 ? first : second);
                field(task.getClass(), "gesture").set(task, BuildPlacementGeometry.currentGesture(h.player, target, Map.of()));
                h.nextTick();
                check(invoke(task, "selectItemTick") == TaskState.RUNNING, "cached selection is immediately usable");
                check(field(task.getClass(), "phase").get(task).toString().equals("AIM"), "no new creative slot transaction for the same material");
                h.set(target.pos(), target.desiredState());
                field(task.getClass(), "useCount").setInt(task, 1);
                invoke(task, "finishPlaced");
                check(field(task.getClass(), "phase").get(task).toString().equals("SELECT"), "cell completion cannot enter per-block creative cleanup");
                check(h.inventory.getItem(0).is(Items.OAK_PLANKS), "the material remains in the creative cache");
                @SuppressWarnings("unchecked")
                var nextUse = (ToIntFunction<Item>) invoke(task, "creativeNeeds");
                check((cache.unused(CreativeBuildMaterialSupply.snapshot(h.player), nextUse).kind() == CreativeBuildInventory.Kind.EVICT)
                                == (index == 1), "cleanup eligibility follows remaining structure demand");
            }
            Field phase = field(task.getClass(), "phase");
            @SuppressWarnings({"rawtypes", "unchecked"})
            Object verifyEnd = Enum.valueOf((Class) phase.getType(), "SCAFFOLD_SELECT");
            phase.set(task, verifyEnd);
            check(task.onTick() == TaskState.RUNNING && phase.get(task).toString().equals("CLEAR_CREATIVE"),
                    "verified full construction enters one final creative cleanup before success");
            check(h.inventory.getItem(1).is(Items.DIAMOND) && h.inventory.getItem(1).getCount() == 64,
                    "pre-existing player items stay outside cache cleanup ownership");
        }
        System.out.println("CreativeBuildRetentionTest: materials survive cell boundaries until no longer needed");
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Object invoke(Object task, String name) throws Exception {
        Method method = task.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(task);
    }
    private static void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
}
