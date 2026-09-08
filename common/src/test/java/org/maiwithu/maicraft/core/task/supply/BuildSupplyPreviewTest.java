package org.maiwithu.maicraft.core.task.supply;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.client.preview.PreviewSession.Decision;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.task.TaskState;
import sun.misc.Unsafe;

/** Real supply startup must reach frozen review before it has any material or acquisition child. */
public final class BuildSupplyPreviewTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var oak = ResourceLocation.parse("minecraft:oak_planks");
        var modded = ResourceLocation.parse("addon:planks");
        var family = new SemanticBuildMaterialBinding.Family("planks", oak, List.of(oak), List.of(modded, oak), 100);
        check(SemanticBuildMaterialBinding.select(family, id -> 0).equals(oak),
                "unobserved registry alternatives cannot replace the design or trigger sample acquisition");
        check(SemanticBuildMaterialBinding.select(family, id -> id.equals(modded) ? 64 : 0).equals(modded),
                "carried modded variants remain eligible without a vanilla whitelist");

        Unsafe memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        FlatLevel level = (FlatLevel) memory.allocateInstance(FlatLevel.class);
        LocalPlayer player = (LocalPlayer) memory.allocateInstance(LocalPlayer.class);
        field(Entity.class, "level").set(player, level);
        field(LocalPlayer.class, "clientLevel").set(player, level);
        Inventory inventory = new Inventory(player);
        field(Player.class, "inventory").set(player, inventory);
        var cell = new BuildTaskRecord.Target(Blocks.OAK_PLANKS, Items.OAK_PLANKS,
                BlockPos.ZERO, "planks", null, null, null);
        var plan = new BuildTaskRecord("preview-before-supply", 1000, List.of(cell), false);
        var record = new SemanticBuildSupplyTaskRecord("supply-preview", 1000, plan);
        var observed = new AtomicReference<BuildTaskRecord>();
        var decision = new AtomicReference<>(Decision.WAITING);
        var reviews = new AtomicInteger();
        var task = new SemanticBuildSupplyCompanionTask(player, record, (owner, frozen) -> {
            reviews.incrementAndGet(); observed.set(frozen); return decision.get();
        });
        task.onStart();
        check((boolean) field(task.getClass(), "prepared").get(task), "palette must bind synchronously with an empty inventory");
        check(!((SemanticMaterialSupplyCoordinator) field(task.getClass(), "supply").get(task)).active(),
                "startup cannot start mining, crafting, moving or storage access to choose a palette");
        for (int tick = 0; tick < 30; tick++) {
            check(task.onTick() == TaskState.RUNNING, "waiting review keeps the task pending");
        }
        check(reviews.get() == 30 && observed.get().targets.getFirst().item() == Items.OAK_PLANKS,
                "the complete concrete plan reaches review before a material batch");
        inventory.setItem(0, new ItemStack(Items.BIRCH_PLANKS, 64));
        task.onTick();
        check(observed.get().targets.getFirst().item() == Items.OAK_PLANKS,
                "inventory changes during review cannot silently recolor the frozen blueprint");
        check(!((SemanticMaterialSupplyCoordinator) field(task.getClass(), "supply").get(task)).active(),
                "waiting review must not start supply even after inventory changes");
        decision.set(Decision.CANCELLED);
        check(task.onTick() == TaskState.CANCELLED, "cancel before approval prevents all acquisition");
        System.out.println("BuildSupplyPreviewTest: frozen review precedes all supply actions");
    }

    private static final class FlatLevel extends ClientLevel {
        private FlatLevel() { super(null, null, null, null, 0, 0, null, null, false, 0); }
        @Override public boolean isLoaded(BlockPos pos) { return true; }
        @Override public BlockState getBlockState(BlockPos pos) { return Blocks.AIR.defaultBlockState(); }
    }
    private static Field field(Class<?> owner, String name) throws Exception {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
