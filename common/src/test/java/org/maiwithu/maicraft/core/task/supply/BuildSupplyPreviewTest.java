package org.maiwithu.maicraft.core.task.supply;

import java.lang.reflect.Field;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
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
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
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
        exactPaletteKeepsWallAndTrim();

        Unsafe memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        constructionReuseKeepsFinalStateObligations(memory);
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

    private static void exactPaletteKeepsWallAndTrim() {
        var wall = new BuildTaskRecord.Target(Blocks.OAK_PLANKS, Items.OAK_PLANKS,
                BlockPos.ZERO, "oak wall", null, null, null);
        var trim = new BuildTaskRecord.Target(Blocks.SPRUCE_PLANKS, Items.SPRUCE_PLANKS,
                BlockPos.ZERO.above(), "spruce trim", null, null, null);
        var plan = new BuildTaskRecord("exact-palette", 1000, List.of(wall, trim), false);
        var proposal = SemanticBuildMaterialBinding.propose(plan, false);
        var selected = new LinkedHashMap<ResourceLocation, ResourceLocation>();
        for (var family : proposal.families()) {
            selected.put(family.groupId(), SemanticBuildMaterialBinding.select(family,
                    id -> id.getPath().equals("spruce_planks") ? 64 : 0));
        }
        var bound = SemanticBuildMaterialBinding.bind(plan, proposal, selected);
        check(bound.targets.getFirst().desiredState().is(Blocks.OAK_PLANKS)
                        && bound.targets.get(1).desiredState().is(Blocks.SPRUCE_PLANKS),
                "a specified palette preserves oak wall and spruce trim even when only spruce is carried");
        check(proposal.groups().size() == 2 && proposal.families().stream()
                        .allMatch(family -> family.alternatives().size() == 1),
                "exact materials have independent supply requirements and no substitute variants");
    }

    private static void constructionReuseKeepsFinalStateObligations(Unsafe memory) throws Exception {
        FlatLevel level = (FlatLevel) memory.allocateInstance(FlatLevel.class);
        LocalPlayer player = (LocalPlayer) memory.allocateInstance(LocalPlayer.class);
        field(Entity.class, "level").set(player, level);
        field(LocalPlayer.class, "clientLevel").set(player, level);
        field(Player.class, "inventory").set(player, new Inventory(player));
        BlockState desired = Blocks.OAK_DOOR.defaultBlockState();
        Set<String> important = Set.of("facing", "half", "hinge", "open");
        level.door(desired.setValue(DoorBlock.OPEN, !desired.getValue(DoorBlock.OPEN)));
        var task = doorTask(player, desired, important);
        task.onStart();
        check(((Map<?, ?>) field(task.getClass(), "initialRemaining").get(task)).isEmpty(),
                "opening an existing door must not add a replacement to the initial material ledger");
        check(((Map<?, ?>) field(task.getClass(), "fullLedger").get(task)).get(Items.OAK_DOOR).equals(1),
                "the full design still costs one door, without charging its generated upper half");
        checkNoReplacement(task);
        check(!(boolean) invoke(task, "allMatched") && (int) invoke(task, "remainingCellCount") == 2,
                "a reusable door with important OPEN mismatch must still reach final state adjustment");

        level.door(desired.setValue(DoorBlock.FACING,
                desired.getValue(DoorBlock.FACING).getClockWise()));
        checkNoReplacement(task);
        check(!(boolean) invoke(task, "allMatched"),
                "a required orientation mismatch needs finalization rather than replacement acquisition");
        level.door(desired);
        check((boolean) invoke(task, "allMatched") && (int) invoke(task, "remainingCellCount") == 0,
                "all important properties must match before the supply parent considers the plan finished");

        level.door(desired.setValue(DoorBlock.OPEN, !desired.getValue(DoorBlock.OPEN)));
        var unimportantOpen = doorTask(player, desired, Set.of("facing", "half", "hinge"));
        unimportantOpen.onStart();
        checkNoReplacement(unimportantOpen);
        check((boolean) invoke(unimportantOpen, "allMatched"),
                "an explicitly unimportant OPEN property must not block final completion");

        var legacy = doorTask(player, desired, null);
        legacy.onStart();
        checkReplacement(legacy, "legacy exact OPEN constraints retain their existing material behavior");
        level.door(Blocks.IRON_DOOR.defaultBlockState());
        checkReplacement(task, "a different block identity must still require the requested material");
        level.door(Blocks.AIR.defaultBlockState());
        checkReplacement(task, "an absent door must still require placement material");
        level.door(desired);
        level.unloaded = true;
        checkReplacement(task, "unloaded targets cannot count as reusable inventory evidence");
    }

    private static SemanticBuildSupplyCompanionTask doorTask(LocalPlayer player, BlockState desired,
                                                             Set<String> finalProperties) {
        var lower = doorTarget(desired, BlockPos.ZERO, finalProperties);
        var upper = doorTarget(desired.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER),
                BlockPos.ZERO.above(), finalProperties);
        var plan = new BuildTaskRecord("door-final-properties", 1000, List.of(lower, upper), false);
        var record = new SemanticBuildSupplyTaskRecord("door-materials", 1000, plan,
                SemanticMaterialSupplyCoordinator.MaterialPolicy.ORDINARY, List.of(), false, List.of(), false);
        return new SemanticBuildSupplyCompanionTask(player, record, (owner, frozen) -> Decision.WAITING);
    }

    private static BuildTaskRecord.Target doorTarget(BlockState desired, BlockPos pos,
                                                     Set<String> finalProperties) {
        return new BuildTaskRecord.Target(desired, Items.OAK_DOOR, pos, "door",
                desired.getValue(DoorBlock.FACING), null, null, false, Set.of("facing", "half", "hinge", "open"),
                true, finalProperties);
    }

    private static void checkNoReplacement(SemanticBuildSupplyCompanionTask task) throws Exception {
        check(((Map<?, ?>) invoke(task, "ledger", true)).isEmpty(),
                "reusable block state changes must not enter the remaining material ledger");
        check(invoke(task, "nextNeed") == null,
                "a state-only adjustment must reach construction without an acquisition child");
    }

    private static void checkReplacement(SemanticBuildSupplyCompanionTask task, String detail) throws Exception {
        check(Integer.valueOf(1).equals(((Map<?, ?>) invoke(task, "ledger", true)).get(Items.OAK_DOOR))
                && invoke(task, "nextNeed") != null, detail);
    }

    private static Object invoke(Object target, String name, Object... args) throws Exception {
        var method = target.getClass().getDeclaredMethod(name,
                args.length == 0 ? new Class<?>[0] : new Class<?>[]{boolean.class});
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static final class FlatLevel extends ClientLevel {
        private Map<BlockPos, BlockState> blocks;
        private boolean unloaded;
        private FlatLevel() { super(null, null, null, null, 0, 0, null, null, false, 0); }
        void door(BlockState lower) {
            blocks = Map.of(BlockPos.ZERO, lower, BlockPos.ZERO.above(),
                    lower.hasProperty(DoorBlock.HALF)
                            ? lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER) : lower);
        }
        @Override public boolean isLoaded(BlockPos pos) { return !unloaded; }
        @Override public BlockState getBlockState(BlockPos pos) {
            return blocks == null ? Blocks.AIR.defaultBlockState()
                    : blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
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
