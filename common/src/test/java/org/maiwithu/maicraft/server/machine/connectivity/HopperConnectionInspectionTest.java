// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.connectivity;

import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.maiwithu.maicraft.server.inventory.NativeItemPort;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;
import org.maiwithu.maicraft.server.machine.NativeApi;
import net.minecraft.world.Container;

/** 空桶供料前只证明组件样品能通过；方向、红石、缺失的内部通道及任一拒绝端口仍必须阻止肯定结论。 */
public final class HopperConnectionInspectionTest {
    private static final BlockPos ORIGIN = new BlockPos(3, 40, 7);
    private static RegistryAccess registries;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        directionsFollowNativeHopperMechanisms();
        passiveLockedHopperStillAcceptsTheOtherDriversTransfer();
        adapterAndTransitDoNotFollowMachineBrand();
        emptySourceAdmissionNeverInventsStock();
        customOrComponentUnknownSourcesStayUnknown();
        rejectingDestinationAndDisconnectedTransitStayBlocked();
        actualAdapterAndSourceOwnershipRemainRequired();
        nativeUnfilteredWrappersAdmitFutureSupply(List.of(args).contains("--require-neoforge"));
        System.out.println("HopperConnectionInspectionTest: 7 policy and native-container groups passed");
    }

    private static void directionsFollowNativeHopperMechanisms() {
        var hopper = hopper(ORIGIN, Direction.EAST, true);
        var above = barrel(ORIGIN.above());
        var east = barrel(ORIGIN.east());
        var pull = HopperConnectionInspection.routes(above, hopper);
        var push = HopperConnectionInspection.routes(hopper, east);
        check(pull.size() == 1 && pull.getFirst().pull() && pull.getFirst().side() == Direction.DOWN, "pull reads the source's down face");
        check(push.size() == 1 && !push.getFirst().pull() && push.getFirst().side() == Direction.WEST, "push reads the receiver's opposite face");
        check(HopperConnectionInspection.routes(hopper, above).isEmpty(), "a nearby container in the wrong direction is not connected");
        check(HopperConnectionInspection.routes(east, hopper).isEmpty(), "hopper does not pull from its side");
    }

    private static void passiveLockedHopperStillAcceptsTheOtherDriversTransfer() {
        // 上方推出与下方抽入是两条独立动作；只检查这次动作的驱动漏斗是否被锁，而非要求两端都解锁。
        for (boolean top : List.of(false, true)) for (boolean bottom : List.of(false, true)) {
            var routes = HopperConnectionInspection.routes(hopper(ORIGIN.above(), Direction.DOWN, top), hopper(ORIGIN, Direction.EAST, bottom));
            check(routes.size() == 2, "both native mechanisms exist vertically");
            check(routes.stream().anyMatch(HopperConnectionInspection.Route::enabled) == (top || bottom), "either enabled driver may move the item");
        }
    }

    private static void adapterAndTransitDoNotFollowMachineBrand() {
        var hopper = hopper(ORIGIN, Direction.DOWN, true);
        // 新中立查询与旧请求提示都要落到同一原生漏斗规则，避免升级后服务端只认识旧系统名。
        for (String hint : List.of("minecraft", "create", "ae2", "mekanism"))
            check(ConnectionAdapterDispatch.HOPPER.equals(ConnectionAdapterDispatch.adapter(hint, "items", barrel(ORIGIN.above()), hopper)), "native items mechanism overrides endpoint hint");
        check("create".equals(ConnectionAdapterDispatch.adapter("create", "kinetic", hopper, barrel(ORIGIN.below()))), "kinetic queries keep their existing adapter");
        check(HopperConnectionInspection.transit(hopper).connected(), "native hopper has one transit inventory");
        check(!HopperConnectionInspection.transit(barrel(ORIGIN)).connected(), "two arbitrary inventory faces do not prove internal transit");
    }

    private static void emptySourceAdmissionNeverInventsStock() {
        var hopper = hopper(ORIGIN, Direction.DOWN, true);
        var inventory = new SimpleContainer(3);
        var result = HopperItemRouteEvidence.probe(registries, new HopperConnectionInspection.Route(hopper, ORIGIN.above(), Direction.DOWN, true),
                new NativeItemPort.ContainerPort(inventory, Direction.DOWN), request(new ItemStack(Items.IRON_INGOT)));
        check("verified".equals(result.get("status").getAsString()), "native source rules and simulated destination admit the planned exact item");
        check(!result.get("source_presence_verified").getAsBoolean() && result.get("source_presence_required").getAsBoolean(), "future supply remains required");
        check(!result.get("source_extraction_simulated").getAsBoolean() && !result.get("flow_verified").getAsBoolean(), "hypothetical source admission is neither an extraction nor flow");
        check(inventory.isEmpty() && hopper.isEmpty(), "inspection leaves both inventories empty");
    }

    private static void customOrComponentUnknownSourcesStayUnknown() {
        var route = new HopperConnectionInspection.Route(hopper(ORIGIN, Direction.DOWN, true), ORIGIN.above(), Direction.DOWN, true);
        var opaque = new ProbePort(ItemStack.EMPTY, true, true);
        check("unknown".equals(HopperItemRouteEvidence.probe(registries, route, opaque, request(new ItemStack(Items.IRON_INGOT))).get("status").getAsString()), "custom empty handler has no hypothetical extraction proof");
        ItemStack named = new ItemStack(Items.IRON_INGOT); named.set(DataComponents.CUSTOM_NAME, Component.literal("component-sensitive"));
        check("unknown".equals(HopperItemRouteEvidence.probe(registries, route,
                new NativeItemPort.ContainerPort(new SimpleContainer(3), Direction.DOWN), request(named)).get("status").getAsString()), "default stack cannot impersonate a named opaque identity");
        var observed = new ProbePort(named, true, true);
        check("verified".equals(HopperItemRouteEvidence.probe(registries, route, observed, request(named)).get("status").getAsString()), "real component sample may be simulated through a custom source");
        check(observed.stack.getCount() == 1, "sample inspection does not consume source contents");
    }

    private static void rejectingDestinationAndDisconnectedTransitStayBlocked() {
        var route = new HopperConnectionInspection.Route(hopper(ORIGIN, Direction.EAST, true), ORIGIN.east(), Direction.WEST, false);
        check("planned".equals(HopperItemRouteEvidence.probe(registries, route,
                new ProbePort(ItemStack.EMPTY, false, false), request(new ItemStack(Items.IRON_INGOT))).get("status").getAsString()), "handler existence cannot override item rejection");
        var edge = ConnectionEvidence.of("verified", true, true, "native_edge", "test");
        var guarded = ConnectionAdapterDispatch.requireTransit(edge, HopperConnectionInspection.transit(barrel(ORIGIN)));
        check(!guarded.connected() && !guarded.operational(), "unproven intermediate inventory breaks the full path");
    }

    private static void nativeUnfilteredWrappersAdmitFutureSupply(boolean required) throws Exception {
        // 独立回归可加载实装 NeoForge wrapper，空源只做模拟；要求该平台时不允许静默跳过原生分支。
        String api = "net.neoforged.neoforge.items.wrapper.InvWrapper";
        if (!NativeApi.present(api)) { check(!required, "installed NeoForge wrapper required for this test"); return; }
        var source = new SimpleContainer(2);
        Object wrapper = NativeApi.type(api).getConstructor(Container.class).newInstance(source);
        var route = new HopperConnectionInspection.Route(hopper(ORIGIN, Direction.DOWN, true), ORIGIN.above(), Direction.DOWN, true);
        var result = HopperItemRouteEvidence.probe(registries, route, new NativeItemPort.CapabilityPort(wrapper), request(new ItemStack(Items.IRON_INGOT)));
        check("verified".equals(result.get("status").getAsString()) && source.isEmpty() && route.hopper().isEmpty(),
                "real InvWrapper can admit future source supply without inserting any item");
    }

    private static void actualAdapterAndSourceOwnershipRemainRequired() {
        // 请求沿用 ae2 提示也不能把实际 Mek 管道误判为缺少 AE 内部节点；但任一原生边失败仍会阻断。
        var verified = ConnectionEvidence.of("verified", true, true, "native_edge", "test");
        var missing = ConnectionEvidence.of("unknown", false, false, "missing_edge", "test");
        check(!ConnectionAdapterDispatch.needsTransit("mekanism", "mekanism"), "same native Mek path needs no AE transit call");
        check(!ConnectionAdapterDispatch.wireTransit(barrel(ORIGIN), "mekanism", "mekanism", verified, verified).connected(), "two native pipe edges cannot prove an arbitrary inventory's internal channel");
        check(!ConnectionAdapterDispatch.wireTransit(null, "mekanism", "mekanism", verified, missing).connected(), "missing native node or edge cannot produce a positive legacy row");
        check(!ConnectionAdapterDispatch.wireTransit(barrel(ORIGIN), "mekanism", "ae2", verified, verified).connected(), "mixed internal channels remain unproven");
        var route = new HopperConnectionInspection.Route(hopper(ORIGIN, Direction.EAST, true), ORIGIN.east(), Direction.WEST, false);
        var sample = new ItemStack(Items.IRON_INGOT);
        check(HopperItemRouteEvidence.futureExtraction(new NativeItemPort.ContainerPort(route.hopper(), null), route, 0, sample), "push may use only its own native hopper inventory");
        check(!HopperItemRouteEvidence.futureExtraction(new NativeItemPort.ContainerPort(new SimpleContainer(2), null), route, 0, sample), "another container cannot impersonate the source hopper");
        check(!HopperItemRouteEvidence.futureExtraction(new ProbePort(ItemStack.EMPTY, true, true), route, 0, sample), "custom handler cannot bypass extraction proof using push");
    }

    private static HopperBlockEntity hopper(BlockPos pos, Direction facing, boolean enabled) {
        return new HopperBlockEntity(pos, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, facing).setValue(HopperBlock.ENABLED, enabled));
    }
    private static BarrelBlockEntity barrel(BlockPos pos) { return new BarrelBlockEntity(pos, Blocks.BARREL.defaultBlockState()); }
    private static JsonObject request(ItemStack stack) {
        JsonObject request = new JsonObject(); request.addProperty("resource", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        request.addProperty("resource_id", ResourceIdentity.key(ResourceIdentity.item(stack, registries))); return request;
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }

    /** 只允许模拟的端口替身；一旦预检尝试真实转移便让回归失败，防止检查暗中改变库存。 */
    private record ProbePort(ItemStack stack, boolean accepts, boolean extracts) implements NativeItemPort {
        public int slots() { return 1; }
        public ItemStack stack(int slot) { return stack; }
        public int limit(int slot) { return 64; }
        public boolean valid(int slot, ItemStack item) { return accepts; }
        public ItemStack insert(int slot, ItemStack item, boolean simulate) {
            check(simulate, "inspection attempted actual insertion"); return accepts ? ItemStack.EMPTY : item.copy();
        }
        public ItemStack extract(int slot, int amount, boolean simulate) {
            check(simulate, "inspection attempted actual extraction"); return extracts && !stack.isEmpty() ? stack.copyWithCount(1) : ItemStack.EMPTY;
        }
    }
}
