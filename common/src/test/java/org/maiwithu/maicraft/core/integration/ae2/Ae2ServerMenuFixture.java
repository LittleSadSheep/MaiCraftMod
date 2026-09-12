// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ae2;

import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuVisibility;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.client.server.ClientOperation;
import org.maiwithu.maicraft.client.server.ClientRequestRouter;
import org.maiwithu.maicraft.client.server.ServerSessionRuntime;
import sun.misc.Unsafe;

/** Inert menu/AE host fixture: only the production actor visibility and session state machines run. */
final class Ae2ServerMenuFixture implements AutoCloseable {
    final InteractionWorldTestHarness world = new InteractionWorldTestHarness();
    final Minecraft minecraft = Minecraft.getInstance();
    final Unsafe memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
    final BlockPos position = new BlockPos(4, 1, 4);
    final Cable cable = new Cable(position);
    final Storage menu = new Storage(7, cable.east);
    final Ae2ReflectionBridge bridge = (Ae2ReflectionBridge) memory.allocateInstance(Ae2ReflectionBridge.class);
    final Ae2TerminalAccess.FixedTarget target = new Ae2TerminalAccess.FixedTarget("fixture", position, Direction.EAST,
            new BlockPos(5, 1, 4), new Vec3(4.95, 1.5, 4.5));
    final Ae2ResourceSupply.Request request = new Ae2ResourceSupply.Request(List.of(
            new Ae2ResourceSupply.Group(ResourceLocation.parse("minecraft:iron_ingot"), 1)), true);
    final List<JsonObject> sent = new ArrayList<>();
    final Field routerField = field(ServerSessionRuntime.class, "router"), installedField = field(ServerSessionRuntime.class, "installed");
    final Object priorRouter = routerField.get(null), priorInstalled = installedField.get(null);
    final ClientRequestRouter router;

    Ae2ServerMenuFixture() throws Exception {
        field(AbstractContainerMenu.class, "carried").set(world.player.inventoryMenu, ItemStack.EMPTY);
        field(Level.class, "worldBorder").set(world.level, new WorldBorder());
        field(Level.class, "isClientSide").setBoolean(world.level, true);
        Object chunks = field(world.level.getClass(), "chunks").get(world.level), chunk = field(chunks.getClass(), "chunk").get(chunks);
        field(chunk.getClass(), "level").set(chunk, world.level); field(ChunkAccess.class, "levelHeightAccessor").set(chunk, world.level);
        field(chunk.getClass(), "blockEntities").set(chunk, new java.util.HashMap<>(Map.of(position, cable)));
        world.set(position, Blocks.BARREL.defaultBlockState()); cable.setLevel(world.level); world.position(new Vec3(5.5, 1, 4.5));
        field(Ae2ReflectionBridge.class, "storageMenuClass").set(bridge, Storage.class);
        field(Ae2ReflectionBridge.class, "craftAmountMenuClass").set(bridge, String.class);
        field(Ae2ReflectionBridge.class, "craftConfirmMenuClass").set(bridge, StringBuilder.class);
        field(Ae2ReflectionBridge.class, "cableBusBlockEntityClass").set(bridge, Cable.class);
        field(Ae2ReflectionBridge.class, "terminalPartClass").set(bridge, Terminal.class);
        field(Ae2ReflectionBridge.class, "cableBusGetPart").set(bridge, Cable.class.getMethod("getPart", Direction.class));
        field(Ae2ReflectionBridge.class, "getLinkStatus").set(bridge, Storage.class.getMethod("getLinkStatus"));
        field(Ae2ReflectionBridge.class, "linkConnected").set(bridge, Link.class.getMethod("connected"));
        router = new ClientRequestRouter(() -> true, envelope -> { sent.add(envelope.deepCopy()); return true; },
                () -> {}, Runnable::run, (receipt, send) -> { send.run(); return true; });
        for (String operation : List.of("inventory.ae2_network", "inventory.ae2_supply"))
            router.register(new ClientOperation(operation, 1, operation.endsWith("supply"), null));
        router.bind(1, 1, "minecraft:overworld", 1, true, 0);
        JsonObject welcome = new JsonObject(); welcome.addProperty("kind", "welcome"); welcome.addProperty("bootstrap", 1);
        welcome.addProperty("status", "succeeded"); welcome.add("clientNonce", sent.getFirst().get("clientNonce"));
        welcome.addProperty("sessionId", "ae-menu-fixture"); welcome.addProperty("dimension", "minecraft:overworld");
        JsonObject features = new JsonObject();
        for (String operation : List.of("inventory.ae2_network", "inventory.ae2_supply")) {
            JsonObject feature = new JsonObject(); feature.addProperty("version", 1); feature.addProperty("enabled", true);
            feature.addProperty("mutating", operation.endsWith("supply")); features.add(operation, feature);
        }
        welcome.add("features", features); router.receive(welcome, 1);
        JsonObject control = sent.getLast().deepCopy(); control.addProperty("status", "succeeded"); router.receive(control, 1);
        routerField.set(null, router); installedField.setBoolean(null, true);
    }

    LocalPlayerContext context() { return ClientRuntime.requireContext(world.player); }
    void next() throws Exception { world.nextTick(); MenuVisibility.rendered(minecraft.screen); }
    void show(Storage selected) throws Exception {
        world.player.containerMenu = selected;
        var screen = (MenuVisibility.PlayerInventoryScreen) memory.allocateInstance(MenuVisibility.PlayerInventoryScreen.class);
        field(AbstractContainerScreen.class, "menu").set(screen, selected); minecraft.screen = screen;
    }
    Ae2ServerMenuPresentation presentation() { return new Ae2ServerMenuPresentation(menu, () -> bridge.matchesFixedTerminalMenu(menu, cable, Direction.EAST)); }
    void awaitVisible(Ae2ServerMenuPresentation presentation) throws Exception {
        for (int i = 0; i < 10; i++) { if (presentation.ready(context())) return; next(); }
        throw new AssertionError("fixture screen did not satisfy the real actor visibility gate");
    }
    public void close() throws Exception { routerField.set(null, priorRouter); installedField.set(null, priorInstalled); world.close(); }
    static Field field(Class<?> owner, String name) throws Exception {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try { var field = type.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException missing) { }
        }
        throw new NoSuchFieldException(name);
    }
    public static final class Terminal {}
    public static final class Cable extends BarrelBlockEntity {
        final Terminal east = new Terminal(), west = new Terminal();
        Cable(BlockPos pos) { super(pos, Blocks.BARREL.defaultBlockState()); }
        public Object getPart(Direction side) { return side == Direction.EAST ? east : side == Direction.WEST ? west : null; }
    }
    public record Link(boolean connected) {}
    public static final class Storage extends AbstractContainerMenu {
        final Object host;
        Storage(int id, Object host) { super(null, id); this.host = host; }
        public Object getHost() { return host; }
        public Link getLinkStatus() { return new Link(true); }
        public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
        public boolean stillValid(Player player) { return true; }
    }
}
