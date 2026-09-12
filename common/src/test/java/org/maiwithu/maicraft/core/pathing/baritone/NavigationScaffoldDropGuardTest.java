// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.baritone;

import baritone.api.IBaritone;
import baritone.api.BaritoneAPI;
import baritone.api.utils.IPlayerContext;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;
import org.maiwithu.maicraft.core.pathing.moves.movements.BuildPlacementRegistry;

/** The real unprotected-support right-click path must consult the provider's positional policy. */
public final class NavigationScaffoldDropGuardTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var backend = field(EmbeddedBaritoneRuntime.class, "backend"); var owner = field(EmbeddedBaritoneRuntime.class, "owner");
        var provider = field(BuildPlacementRegistry.class, "activeProvider");
        Object oldBackend = backend.get(null), oldOwner = owner.get(null), oldProvider = provider.get(null);
        var oldPolicy = EmbeddedBaritonePolicy.snapshot(); Boolean oldPlace = null;
        try (var h = new InteractionWorldTestHarness()) {
            // Baritone boot reads its settings path and creates its private backend directory.
            field(net.minecraft.client.Minecraft.class, "gameDirectory").set(net.minecraft.client.Minecraft.getInstance(),
                    java.nio.file.Path.of("navigation-scaffold-settings-fixture").toAbsolutePath().toFile());
            oldPlace = BaritoneAPI.getSettings().allowPlace.value;
            h.inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
            var hit = new BlockHitResult(new Vec3(4.5, 1, 4.5), Direction.UP, new BlockPos(4, 0, 4), false);
            var policy = new Policy(); var nav = new EmbeddedBaritoneNavigator(h.player, () -> null, () -> false, policy, false);
            var playerContext = (IPlayerContext) Proxy.newProxyInstance(IPlayerContext.class.getClassLoader(), new Class<?>[]{IPlayerContext.class},
                    (proxy, method, values) -> switch (method.getName()) {
                        case "player" -> h.player;
                        case "objectMouseOver" -> hit;
                        default -> throw new AssertionError("unexpected player context access: " + method.getName());
                    });
            backend.set(null, Proxy.newProxyInstance(IBaritone.class.getClassLoader(), new Class<?>[]{IBaritone.class},
                    (proxy, method, values) -> method.getName().equals("getPlayerContext") ? playerContext : null));
            owner.set(null, nav); provider.set(null, null); EmbeddedBaritonePolicy.clear(); BaritoneAPI.getSettings().allowPlace.value = true;
            var bridge = new EmbeddedBaritoneActionBridge();
            var use = bridge.getClass().getDeclaredMethod("startUse", org.maiwithu.maicraft.client.actor.LocalPlayerContext.class,
                    EmbeddedBaritoneNavigator.class, boolean.class); use.setAccessible(true);
            use.invoke(bridge, ClientRuntime.requireContext(h.player), nav, false);
            check(policy.checked.equals(hit.getBlockPos().above()) && h.blockUses() == 0,
                    "unprotected support cannot bypass the exact destination's debris policy");
            var frozen = EmbeddedBaritonePolicy.capture(null, nav.protectedMutationCells(), null);
            Field unsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); unsafe.setAccessible(true);
            var calculation = (baritone.pathing.movement.CalculationContext) ((sun.misc.Unsafe) unsafe.get(null))
                    .allocateInstance(baritone.pathing.movement.CalculationContext.class);
            field(calculation.getClass(), "hasThrowaway").setBoolean(calculation, true);
            field(calculation.getClass(), "maicraftPolicy").set(calculation, frozen);
            check(calculation.costOfPlacingAt(4, 1, 4, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState())
                            == baritone.api.pathing.movement.ActionCosts.COST_INF && !frozen.forbidsBody(4, 1, 4),
                    "native worker costs exclude the denied placement on replanning without forbidding walking");
            policy.allowed = true;
            nav = new EmbeddedBaritoneNavigator(h.player, () -> null, () -> false, policy, false); owner.set(null, nav);
            use.invoke(bridge, ClientRuntime.requireContext(h.player), nav, false);
            check(h.blockUses() == 1, "an approved destination still uses the ordinary native actor path");
        } finally {
            backend.set(null, oldBackend); owner.set(null, oldOwner); provider.set(null, oldProvider);
            EmbeddedBaritonePolicy.installSnapshot(oldPolicy);
            if (oldPlace != null) BaritoneAPI.getSettings().allowPlace.value = oldPlace;
        }
        System.out.println("NavigationScaffoldDropGuardTest: positional denial also covers unprotected native placement");
    }

    private static final class Policy implements PlayerNav.ContextProvider, BuildPlacementRegistry.Provider {
        boolean allowed; BlockPos checked;
        public TerrainPermit permit() { return TerrainPermit.TERRAFORM; }
        public BlockState desiredState(BlockPos pos) { return null; }
        public boolean permitsTemporaryScaffold(BlockPos pos) { checked = pos; return allowed; }
    }
    private static Field field(Class<?> type, String name) throws Exception { var field = type.getDeclaredField(name); field.setAccessible(true); return field; }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
