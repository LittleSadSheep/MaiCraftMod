package org.maiwithu.maicraft.core.integration.ae2;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import sun.misc.Unsafe;
import java.util.function.Predicate;

/** 新的存入范围约束要传到会话并随时可撤销，不能改变旧取料／网络准备的默认终端选择。 */
public final class Ae2DepositAccessPolicyTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Field raw = Unsafe.class.getDeclaredField("theUnsafe"); raw.setAccessible(true); Unsafe memory = (Unsafe) raw.get(null);
        try (var world = new InteractionWorldTestHarness()) {
            var bridge = (Ae2ReflectionBridge) memory.allocateInstance(Ae2ReflectionBridge.class);
            var groups = List.of(new Ae2ResourceSupply.Group(ResourceLocation.parse("minecraft:dirt"), 1));
            var request = new Ae2ResourceSupply.Request(groups, false, Ae2ResourceSupply.Operation.DEPOSIT);
            BlockPos origin = new BlockPos(2, 1, 2), inside = origin.east(), outside = origin.east(9);
            AtomicBoolean enabled = new AtomicBoolean(true);
            Predicate<BlockPos> scope = at -> enabled.get() && at.distSqr(origin) <= 16;
            var record = Ae2ResourceSupply.taskRecord("deposit-scope", 1000, request, scope);
            check(record.depositAccess.test(inside) && !record.depositAccess.test(outside), "task-record factory preserves the caller's fixed access scope");
            var deposit = new Ae2SupplySession(world.player, request, bridge, false, record.depositAccess);
            check(allowed(deposit, inside) && !allowed(deposit, outside), "distant remembered or discovered fixed access is denied");
            check(!NavigationSafetyContext.withProtectedArea(List.of(inside), List.of(), () -> allowed(deposit, inside)),
                    "explicit no-use protection wins even when the caller's radius permits the terminal");
            enabled.set(false);
            check(!allowed(deposit, inside), "a policy changed before another GUI deposit must be re-evaluated");
            var supply = new Ae2SupplySession(world.player, new Ae2ResourceSupply.Request(groups, false), bridge, false, scope);
            var prepare = new Ae2SupplySession(world.player, new Ae2ResourceSupply.Request(groups, false, Ae2ResourceSupply.Operation.PREPARE), bridge, false, scope);
            check(allowed(supply, outside) && allowed(prepare, outside), "new deposit access rules do not change legacy supply/prepare behavior");
            check(Ae2ResourceSupply.taskRecord("default-scope", 1000, request).depositAccess.test(outside), "old constructors keep a permissive optional deposit scope");
        }
        System.out.println("Ae2DepositAccessPolicyTest: task wiring, bounded fixed access, revocation and legacy defaults passed");
    }
    private static boolean allowed(Ae2SupplySession session, BlockPos at) {
        try {
            var method = Ae2SupplySession.class.getDeclaredMethod("fixedAccessAllowed", BlockPos.class); method.setAccessible(true);
            return (Boolean) method.invoke(session, at);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
