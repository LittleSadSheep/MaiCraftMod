package org.maiwithu.maicraft.core.pathing.baritone;

import baritone.api.IBaritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.behavior.LookBehavior;
import baritone.behavior.PathingBehavior;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.movements.MovementPillar;
import baritone.pathing.movement.movements.MovementTraverse;
import baritone.pathing.path.PathExecutor;
import baritone.utils.InputOverrideHandler;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.OptionalLong;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.DimensionType;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuPort;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.settings.ScaffoldMaterials;
import sun.misc.Unsafe;

/** 回放背包未绘制、关包占用额度和材料槽位变化，确认这些条件不会在首条路线前封死整场导航。 */
public final class NavigationStartupPreparationTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var w = new InteractionWorldTestHarness()) {
            var memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
            // 首个触发 Baritone 初始化的测试也提供正常的游戏目录，不能借用其他测试预热的静态状态。
            field(Minecraft.class, "gameDirectory").set(Minecraft.getInstance(), new File("navigation-startup-fixture"));
            field(Minecraft.class, "gameThread").set(Minecraft.getInstance(), Thread.currentThread());
            // 保留一个从未渲染的真实背包界面；旧实现会等待它，新实现应先算路而不是触发界面操作。
            var menu = new InventoryMenu(w.inventory, true, w.player);
            field(Player.class, "inventoryMenu").set(w.player, menu); w.player.containerMenu = menu;
            var screen = (InventoryScreen) memory.allocateInstance(InventoryScreen.class);
            field(AbstractContainerScreen.class, "menu").set(screen, menu); Minecraft.getInstance().screen = screen;
            var dimension = new DimensionType(OptionalLong.empty(), true, false, false, true, 1, true, false, 0, 16, 16,
                    BlockTags.INFINIBURN_OVERWORLD, ResourceLocation.withDefaultNamespace("overworld"), 0,
                    new DimensionType.MonsterSettings(false, false, ConstantInt.of(0), 0));
            field(Level.class, "dimensionTypeRegistration").set(w.level, Holder.direct(dimension));
            var oldBackend = field(EmbeddedBaritoneRuntime.class, "backend").get(null);
            var oldOwner = field(EmbeddedBaritoneRuntime.class, "owner").get(null);
            var oldWorld = field(EmbeddedBaritoneRuntime.class, "world").get(null);
            var materials = ScaffoldMaterials.storedIds(w.player);
            var selection = (EmbeddedBuildScaffoldSelection) field(EmbeddedBaritoneRuntime.class, "BUILD_SCAFFOLDS").get(null);
            var nav = new EmbeddedBaritoneNavigator(w.player, () -> null, () -> false, PlayerNav.ContextProvider.TERRAFORM, false);
            try {
                ScaffoldMaterials.store(w.player, ScaffoldMaterials.factoryDefaultIds());
                var pathing = (PathingBehavior) memory.allocateInstance(PathingBehavior.class);
                var inputs = (InputOverrideHandler) memory.allocateInstance(InputOverrideHandler.class);
                field(InputOverrideHandler.class, "inputForceStateMap").set(inputs, new HashMap<>());
                var look = (LookBehavior) memory.allocateInstance(LookBehavior.class);
                field(LookBehavior.class, "smoothYawBuffer").set(look, new ArrayDeque<>());
                field(LookBehavior.class, "smoothPitchBuffer").set(look, new ArrayDeque<>());
                var playerContext = (IPlayerContext) Proxy.newProxyInstance(IPlayerContext.class.getClassLoader(), new Class<?>[]{IPlayerContext.class},
                        (proxy, method, values) -> switch (method.getName()) {
                            case "player" -> w.player;
                            case "world" -> w.level;
                            case "worldData" -> null;
                            case "minecraft" -> Minecraft.getInstance();
                            default -> throw new AssertionError(method.getName());
                        });
                var backend = (IBaritone) Proxy.newProxyInstance(IBaritone.class.getClassLoader(), new Class<?>[]{IBaritone.class},
                        (proxy, method, values) -> switch (method.getName()) {
                            case "getPlayerContext" -> playerContext;
                            case "getPathingBehavior" -> pathing;
                            case "getInputOverrideHandler" -> inputs;
                            case "getLookBehavior" -> look;
                            default -> throw new AssertionError(method.getName());
                        });
                field(EmbeddedBaritoneRuntime.class, "backend").set(null, backend);
                field(EmbeddedBaritoneRuntime.class, "owner").set(null, nav);
                field(EmbeddedBaritoneRuntime.class, "world").set(null, w.level);
                int[] opens = {0}; long[] tick = {1}; boolean[] budget = {true};
                var menus = (MenuPort) Proxy.newProxyInstance(MenuPort.class.getClassLoader(), new Class<?>[]{MenuPort.class},
                        (proxy, method, values) -> {
                            if (method.getName().equals("ensureVisible")) { opens[0]++; return false; }
                            throw new AssertionError(method.getName());
                        });
                var context = (LocalPlayerContext) Proxy.newProxyInstance(LocalPlayerContext.class.getClassLoader(), new Class<?>[]{LocalPlayerContext.class},
                        (proxy, method, values) -> switch (method.getName()) {
                            case "player" -> w.player;
                            case "level" -> w.level;
                            case "menus" -> menus;
                            case "tickRevision" -> tick[0];
                            case "mutationAvailable" -> budget[0];
                            case "isCurrent", "permitsNativeActions" -> true;
                            case "requireCurrent" -> null;
                            default -> throw new AssertionError(method.getName());
                        });
                var prepare = EmbeddedBaritoneRuntime.class.getDeclaredMethod("prepareBuildScaffold", LocalPlayerContext.class, EmbeddedBaritoneNavigator.class);
                prepare.setAccessible(true);
                // 所有背包槽位逐一作为启动状态；尚未生成路线时，材料位置和界面能否绘制都不应挡住搜索。
                for (int slot = 0; slot < 36; slot++) {
                    w.inventory.clearContent(); w.inventory.setItem(slot, new ItemStack(Items.DIRT, 64));
                    check(!(boolean) prepare.invoke(null, context, nav) && opens[0] == 0, "startup search was gated by material slot " + slot);
                }
                var start = new BetterBlockPos(w.player.blockPosition());
                var executor = (PathExecutor) memory.allocateInstance(PathExecutor.class);
                Movement[] move = {new MovementTraverse(backend, start, start.east())};
                var path = (IPath) Proxy.newProxyInstance(IPath.class.getClassLoader(), new Class<?>[]{IPath.class},
                        (proxy, method, values) -> {
                            if (method.getName().equals("movements")) return List.of(move[0]);
                            throw new AssertionError(method.getName());
                        });
                field(PathExecutor.class, "path").set(executor, path); field(PathingBehavior.class, "current").set(pathing, executor);
                check(!(boolean) prepare.invoke(null, context, nav) && opens[0] == 0, "existing stone path does not need scaffold preparation");
                move[0] = new MovementPillar(backend, start, start.above());
                w.set(start, Blocks.LADDER.defaultBlockState());
                check(!(boolean) prepare.invoke(null, context, nav), "climbing a ladder must not consume scaffold material");
                w.set(start, Blocks.WATER.defaultBlockState()); w.set(start.above(), Blocks.WATER.defaultBlockState());
                check(!(boolean) prepare.invoke(null, context, nav), "swimming up a water column must not open the material inventory");
                w.set(start.above(), Blocks.AIR.defaultBlockState());
                w.set(start, Blocks.AIR.defaultBlockState());
                check((boolean) prepare.invoke(null, context, nav) && selection.pending() && opens[0] == 1, "real pillaring prepares material before the jump");
                // 原生关闭占用了这一刻额度，但所有者没变；保留同一次换料流程，而不是重置成新任务。
                budget[0] = false; tick[0]++;
                EmbeddedBaritoneRuntime.tick(context, false);
                check(selection.pending(), "temporary native budget wait must not cancel inventory preparation");
                budget[0] = true; tick[0] += 201;
                check(!(boolean) selection.advance(context, nav) && !selection.pending() && nav.failType() == FailureType.INTERNAL,
                        "an inventory that never renders must settle with the actual preparation failure");
            } finally {
                selection.cancel(nav); field(EmbeddedBaritoneRuntime.class, "backend").set(null, oldBackend);
                field(EmbeddedBaritoneRuntime.class, "owner").set(null, oldOwner); field(EmbeddedBaritoneRuntime.class, "world").set(null, oldWorld);
                ScaffoldMaterials.store(w.player, materials);
            }
        }
        System.out.println("NavigationStartupPreparationTest: all 36 inventory startup states and GUI wait passed");
    }
    private static Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
