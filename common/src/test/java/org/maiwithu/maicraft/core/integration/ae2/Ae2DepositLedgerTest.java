package org.maiwithu.maicraft.core.integration.ae2;

import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** 普通库存双边同量，恒定库存单独记录原生接收证明；命名土石不借用，存入回执保留真实数量。 */
public final class Ae2DepositLedgerTest {
    private static final ResourceLocation DIRT = ResourceLocation.parse("minecraft:dirt");
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        check(Ae2DepositLedger.observe(64, 0, 100, 36, 200, 200, true, true).verdict() == Ae2DepositLedger.Verdict.WAITING,
                "player debit without network stock update cannot prove a deposit");
        check(Ae2DepositLedger.observe(64, 64, 100, 100, 200, 264, true, true).verdict() == Ae2DepositLedger.Verdict.WAITING,
                "network growth alone cannot consume the requested player stack");
        var partial = Ae2DepositLedger.observe(64, 32, 100, 68, 200, 232, true, true);
        check(partial.verdict() == Ae2DepositLedger.Verdict.CONFIRMED && partial.deposited() == 32,
                "a full network may confirm only the actual partial amount");
        check(Ae2DepositLedger.observe(49, 0, 64, 15, 200, 264, true, true).verdict() == Ae2DepositLedger.Verdict.DIVERGED,
                "a 49-item request cannot accept a 64-item network increase");
        check(Ae2DepositLedger.observe(49, 0, 64, 15, 200, 249, true, false).verdict() == Ae2DepositLedger.Verdict.DIVERGED,
                "changes in other player slots invalidate the exact native click evidence");
        // 网络专用菜单的服务器同步证明由调用方提供；账本仍拒绝混入别的槽变化或普通有限库存。
        check(Ae2DepositLedger.nativeAcceptance(37,0,64,27,Integer.MAX_VALUE,Integer.MAX_VALUE,true,true).deposited()==37,
                "constant reported stock can retain an exact native accepted amount");
        check(Ae2DepositLedger.nativeAcceptance(37,37,64,64,Integer.MAX_VALUE,Integer.MAX_VALUE,true,true).verdict()==Ae2DepositLedger.Verdict.WAITING,
                "no player debit means no accepted item");
        check(Ae2DepositLedger.nativeAcceptance(37,0,64,26,Integer.MAX_VALUE,Integer.MAX_VALUE,true,true).verdict()==Ae2DepositLedger.Verdict.DIVERGED,
                "unrelated inventory loss cannot be attributed to this shift");
        check(Ae2DepositLedger.nativeAcceptance(37,0,64,27,200,200,true,true).verdict()==Ae2DepositLedger.Verdict.WAITING,
                "finite stock continues to require matching growth");
        var named = new ItemStack(Items.DIRT, 12); named.set(DataComponents.CUSTOM_NAME, Component.literal("Do not store"));
        check(!Ae2DepositLedger.ordinary(named), "named or custom-component variants remain carried");
        check(Ae2DepositLedger.networkCount(List.of(new Ae2ReflectionBridge.Entry(DIRT, 1, 20, false, new ItemStack(Items.DIRT)),
                new Ae2ReflectionBridge.Entry(DIRT, 2, 12, false, named)), DIRT) == 20, "network variants do not substitute ordinary dirt");
        boolean denied = false;
        try { new Ae2ResourceSupply.Request(List.of(new Ae2ResourceSupply.Group(DIRT, 49)), true, Ae2ResourceSupply.Operation.DEPOSIT); }
        catch (IllegalArgumentException expected) { denied = true; }
        check(denied, "deposit cannot enable crafting");
        var delta = new Ae2ResourceSupply.GroupDelta(DIRT, List.of(DIRT), Ae2ResourceSupply.SelectionMode.AGGREGATE,
                DIRT, 49, 64, 15, 0, 49, 0, List.of(new Ae2ResourceSupply.ItemDelta(DIRT, 64, 15, 0, 49, true)));
        var outcome = new Ae2ResourceSupply.Outcome(Ae2ResourceSupply.Status.SUCCEEDED, "resources_deposited", "verified", List.of(delta),
                Ae2ResourceSupply.Operation.DEPOSIT, false, 0, 0, true, false, "native_gui", List.of());
        var row = (Map<?, ?>) ((List<?>) outcome.data().get("actual_delta")).getFirst();
        check(row.get("deposited").equals(49) && row.get("missing").equals(0) && !row.containsKey("acquired"),
                "public results name confirmed deposit quantities without negative acquisition arithmetic");
        var early = new Ae2ResourceSupply.Outcome(Ae2ResourceSupply.Status.FAILED, "fixed_terminal_not_found", "no terminal", List.of(),
                Ae2ResourceSupply.Operation.DEPOSIT, false, 0, 0, false, false, "unavailable", List.of());
        check(early.data().get("deposited").equals(Map.of()) && early.data().get("confirmed_deposited_total").equals(0),
                "failure before terminal creation still exposes an explicit zero-deposit receipt");
        System.out.println("Ae2DepositLedgerTest: exact dual deltas, protected variants and deposit outcome contract passed");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
