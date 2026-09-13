package org.maiwithu.maicraft.core.integration.ae2;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import sun.misc.Unsafe;

/** 复现原生客户端的空 locator 与真实宿主对象关系；按实际方块实体、面和范围复查，不伪造 locator。 */
public final class Ae2DepositHostAccessTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        partMenuWithoutLocator(); blockEntityMenuWithoutLocator(); scopeAndMissingHostRemainRejected();
        System.out.println("Ae2DepositHostAccessTest: null client locator, exact host/side, replacement and scope guards passed");
    }
    private static void partMenuWithoutLocator() throws Exception {
        try (var f = new Fixture()) {
            check(f.menu.getLocator() == null, "AE2 fromNetwork leaves the client locator null");
            int reads = f.menu.locatorReads;
            var explicit = Ae2DepositAccess.read(f.menu, f.world.player, f.bridge, f.fixed());
            var preopened = Ae2DepositAccess.read(f.menu, f.world.player, f.bridge, null);
            check(explicit.position().equals(f.at) && explicit.side() == Direction.SOUTH && preopened.position().equals(f.at),
                    "both newly opened and preopened menus bind the actual cable host and terminal face");
            check(f.menu.locatorReads == reads && Ae2DepositAccess.current(explicit, f.menu, f.world.player, f.bridge),
                    "deposit never needs the missing client locator");
            var replacement = new NativePart(f.cable, Direction.SOUTH); f.menu.host = replacement;
            check(!Ae2DepositAccess.current(explicit, f.menu, f.world.player, f.bridge), "a replacement menu host cannot inherit the old deposit");
            f.menu.host = f.part; f.cable.part = replacement;
            check(!Ae2DepositAccess.current(explicit, f.menu, f.world.player, f.bridge), "changing the physical part behind the same GUI is detected");
            f.cable.part = f.part; f.part.side = Direction.NORTH;
            check(!Ae2DepositAccess.current(explicit, f.menu, f.world.player, f.bridge), "the terminal side remains part of the ownership binding");
        }
    }
    private static void blockEntityMenuWithoutLocator() throws Exception {
        try (var f = new Fixture()) {
            f.menu.host = f.cable;
            var bound = Ae2DepositAccess.read(f.menu, f.world.player, f.bridge, null);
            check(bound.side() == null && Ae2DepositAccess.current(bound, f.menu, f.world.player, f.bridge),
                    "a block-entity storage menu uses its loaded host directly");
            var replacement = new NativeCable(f.at); replacement.setLevel(f.world.level); f.entities.put(f.at, replacement);
            check(!Ae2DepositAccess.current(bound, f.menu, f.world.player, f.bridge), "same coordinates cannot substitute another block entity");
            // 模拟终端被拆除时方块也应消失，不能留下箱方块触发原生区块自动补建实体。
            f.entities.clear(); f.world.set(f.at, Blocks.AIR.defaultBlockState());
            check(!Ae2DepositAccess.current(bound, f.menu, f.world.player, f.bridge), "a removed host is not usable even with the old GUI open");
        }
    }
    private static void scopeAndMissingHostRemainRejected() throws Exception {
        try (var f = new Fixture()) {
            var bound = Ae2DepositAccess.read(f.menu, f.world.player, f.bridge, f.fixed());
            AtomicBoolean permitted = new AtomicBoolean(true);
            var request = new Ae2ResourceSupply.Request(List.of(new Ae2ResourceSupply.Group(ResourceLocation.parse("minecraft:dirt"), 1)), false, Ae2ResourceSupply.Operation.DEPOSIT);
            var session = new Ae2SupplySession(f.world.player, request, f.bridge, false, at -> permitted.get() && at.equals(f.at));
            field(Ae2SupplySession.class, "depositAccess").set(session, bound);
            field(Ae2SupplySession.class, "depositMenu").set(session, f.menu);
            var allowed = Ae2SupplySession.class.getDeclaredMethod("depositAccessAllowed"); allowed.setAccessible(true);
            check((Boolean) allowed.invoke(session), "a current permitted native host is usable");
            permitted.set(false);
            check(!(Boolean) allowed.invoke(session), "host-based access does not bypass the caller's fixed-terminal predicate");
            permitted.set(true); f.entities.clear(); f.world.set(f.at, Blocks.AIR.defaultBlockState());
            check(!(Boolean) allowed.invoke(session), "a disappeared physical terminal fails before another deposit");
            f.menu.host = new Object(); boolean refused = false;
            try { Ae2DepositAccess.read(f.menu, f.world.player, f.bridge, null); } catch (Ae2ProtocolException expected) { refused = true; }
            check(refused, "an unverifiable preopened host is rejected instead of guessing its source");
        }
    }
    private static final class Fixture implements AutoCloseable {
        final InteractionWorldTestHarness world = new InteractionWorldTestHarness();
        final BlockPos at = new BlockPos(4, 1, 4);
        final NativeCable cable = new NativeCable(at);
        final NativePart part = new NativePart(cable, Direction.SOUTH);
        final Map<BlockPos, BlockEntity> entities = new HashMap<>();
        final ClientMenu menu = new ClientMenu(part);
        final Ae2ReflectionBridge bridge;
        Fixture() throws Exception {
            world.set(at, Blocks.CHEST.defaultBlockState()); cable.setLevel(world.level); cable.part = part; entities.put(at, cable);
            field(Level.class, "isClientSide").setBoolean(world.level, true); world.player.containerMenu = menu;
            Object cache = field(world.level.getClass(), "chunks").get(world.level);
            Object chunk = field(cache.getClass(), "chunk").get(cache);
            field(chunk.getClass(), "level").set(chunk, world.level); field(ChunkAccess.class, "levelHeightAccessor").set(chunk, world.level);
            field(chunk.getClass(), "blockEntities").set(chunk, entities); field(chunk.getClass(), "pendingBlockEntities").set(chunk, new HashMap<>());
            Field raw = Unsafe.class.getDeclaredField("theUnsafe"); raw.setAccessible(true);
            bridge = (Ae2ReflectionBridge) ((Unsafe) raw.get(null)).allocateInstance(Ae2ReflectionBridge.class);
            field(Ae2ReflectionBridge.class, "storageMenuClass").set(bridge, ClientMenu.class);
            field(Ae2ReflectionBridge.class, "cableBusBlockEntityClass").set(bridge, NativeCable.class);
            field(Ae2ReflectionBridge.class, "terminalPartClass").set(bridge, NativePart.class);
            field(Ae2ReflectionBridge.class, "cableBusGetPart").set(bridge, NativeCable.class.getMethod("getPart", Direction.class));
        }
        Ae2TerminalAccess.FixedTarget fixed() { return new Ae2TerminalAccess.FixedTarget("observed", at, Direction.SOUTH, at.south(), at.getCenter()); }
        @Override public void close() throws Exception { world.close(); }
    }
    public static final class ClientMenu extends AbstractContainerMenu {
        Object host; int locatorReads;
        ClientMenu(Object host) { super(null, 84); this.host = host; }
        public Object getLocator() { locatorReads++; return null; }
        public Object getHost() { return host; }
        @Override public ItemStack quickMoveStack(Player player, int slot) { throw new AssertionError("access observation must not move items"); }
        @Override public boolean stillValid(Player player) { return true; }
    }
    public static final class NativeCable extends BlockEntity {
        NativePart part;
        // 夹具借用原生箱实体类型承载假想终端面，初始化方块必须同类型；真实 AE 接线另由客户端验收。
        NativeCable(BlockPos at) { super(BlockEntityType.CHEST, at, Blocks.CHEST.defaultBlockState()); }
        public Object getPart(Direction side) { return part != null && part.side == side ? part : null; }
    }
    public static final class NativePart {
        final NativeCable cable; Direction side;
        NativePart(NativeCable cable, Direction side) { this.cable = cable; this.side = side; }
        public BlockEntity getBlockEntity() { return cable; }
        public Direction getSide() { return side; }
    }
    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            try { Field field = owner.getDeclaredField(name); field.setAccessible(true); return field; } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
