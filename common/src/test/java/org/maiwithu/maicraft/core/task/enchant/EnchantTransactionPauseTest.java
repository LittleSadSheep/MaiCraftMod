// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.enchant;

import com.mojang.serialization.Lifecycle;
import java.lang.reflect.Proxy;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuConfirmation;
import org.maiwithu.maicraft.client.actor.MenuPort;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.task.TaskState;
import java.lang.reflect.Field;
import java.util.Map;

/** 用真实物品组件和回执对象复现暂停期间的异步确认；只验证控制层证据保留，不伪称本地夹具完成服务器附魔。 */
public final class EnchantTransactionPauseTest {
    private static RegistryAccess registries;
    private static Holder<Enchantment> clue, extra;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); prepareRegistry();
        for (boolean book : new boolean[]{false, true}) confirmedWhilePaused(book);
        pendingCandidateIsNotReceipt(); changedResultAfterConfirmation();
        System.out.println("EnchantTransactionPauseTest: paused confirmation snapshots, pending candidates and return ownership passed");
    }

    private static void confirmedWhilePaused(boolean book) throws Exception {
        try (var f = new Fixture(book)) {
            f.submit(); f.deliverResult();
            check(f.observeInBackground(false) == MenuConfirmation.Verdict.APPLIED && !f.receipt.terminal(),
                    "a matching result without final synchronization remains only a candidate");
            // 暂停期间仍可能有后续成品同步；最终确认必须保存最后一次完整匹配，包含报价以外的全部附魔。
            f.menu.getSlot(0).getItem().enchant(extra, 4); f.observeInBackground(true);
            check(!f.transaction.confirmed(), "the paused task has not yet claimed its completed receipt");
            ItemStack confirmed = f.menu.getSlot(0).getItem().copy(); f.world.player.experienceLevel++;
            check(f.flow.tick(f.context) == TaskState.RUNNING && f.transaction.confirmed(),
                    "resuming after a later experience pickup preserves the confirmed enchantment");
            var data = f.flow.data();
            check(data.get("actual_levels_spent").equals(3) && data.get("actual_lapis_spent").equals(3)
                            && data.get("player_level_after_enchantment").equals(32),
                    "reported costs describe the confirmation tick rather than the player's later level");
            check(ItemStack.matches(confirmed, f.transaction.confirmedResult())
                            && f.inventory.ownedContents(f.menu, true, 0) && data.get("phase").equals("return_item"),
                    "the resumed return is bound to the complete confirmed output snapshot");
            f.transaction.confirmedResult().set(DataComponents.CUSTOM_NAME, Component.literal("外部修改副本"));
            check(ItemStack.matches(confirmed, f.transaction.confirmedResult()), "callers cannot mutate historical output evidence");
            check(data.get("result_enchantments").equals(Map.of("minecraft:unbreaking", 3, "minecraft:efficiency", 4))
                            && f.buttons == 1 && !Boolean.TRUE.equals(data.get("gui_closed")),
                    "all confirmed enchantments are visible without replaying the button or claiming cleanup finished");
        }
    }

    private static void pendingCandidateIsNotReceipt() throws Exception {
        try (var f = new Fixture(false)) {
            f.submit(); f.deliverResult(); f.observeInBackground(false);
            check(!f.transaction.poll(f.context) && !f.transaction.confirmed(), "candidate evidence alone cannot complete a pending receipt");
            rejects(f.transaction::confirmedResult, "result_not_confirmed");
            // 尚未确认时又出现不符的扣级，端口判分歧；先前的一帧匹配不能越过最终失败回执。
            f.world.player.experienceLevel = 31;
            check(f.observeInBackground(true) == MenuConfirmation.Verdict.DIVERGED, "new mismatched cost invalidates the unconfirmed candidate");
            rejects(() -> f.transaction.poll(f.context), "outcome_uncertain");
            check(!f.transaction.confirmed() && f.buttons == 1, "a diverged receipt never promotes old evidence or retries consumption");
        }
    }

    private static void changedResultAfterConfirmation() throws Exception {
        try (var f = new Fixture(false)) {
            f.submit(); f.deliverResult(); f.observeInBackground(true);
            f.menu.getSlot(0).set(new ItemStack(Items.DIAMOND_SWORD)); f.world.player.experienceLevel++;
            check(f.flow.tick(f.context) == TaskState.RUNNING && f.transaction.confirmed(), "historical confirmation survives a later workstation change");
            check(f.flow.tick(f.context) == TaskState.FAILED && f.menu.getSlot(0).getItem().is(Items.DIAMOND_SWORD),
                    "the replacement item cannot be claimed or taken as the confirmed pickaxe");
            check(Boolean.FALSE.equals(f.flow.data().get("mechanical_retry_allowed")) && f.buttons == 1,
                    "lost return ownership preserves the one-consumption boundary");
            f.flow.cleanup();
        }
    }

    private static void prepareRegistry() {
        // 只给同步线索和组件建立稳定的名字，不运行原版随机附魔算法。
        var registry = new MappedRegistry<Enchantment>(Registries.ENCHANTMENT, Lifecycle.stable());
        var definition = Enchantment.definition(HolderSet.direct(Items.DIAMOND_PICKAXE.builtInRegistryHolder()),
                1, 5, Enchantment.constantCost(1), Enchantment.constantCost(30), 1, EquipmentSlotGroup.MAINHAND);
        clue = Registry.registerForHolder(registry, Enchantments.UNBREAKING,
                new Enchantment(Component.literal("耐久"), definition, HolderSet.direct(), DataComponentMap.EMPTY));
        extra = Registry.registerForHolder(registry, Enchantments.EFFICIENCY,
                new Enchantment(Component.literal("效率"), definition, HolderSet.direct(), DataComponentMap.EMPTY));
        registry.freeze(); registries = new RegistryAccess.ImmutableRegistryAccess(List.of(registry));
    }

    private static final class Fixture implements AutoCloseable {
        final InteractionWorldTestHarness world = new InteractionWorldTestHarness();
        final EnchantmentMenu menu;
        final EnchantInventory inventory;
        final EnchantMenuFlow flow;
        final EnchantTransaction transaction;
        final LocalPlayerContext context;
        final ItemStack original;
        MenuReceipt receipt;
        MenuConfirmation confirmation;
        int buttons;
        long tick = 1;

        Fixture(boolean book) throws Exception {
            field(Level.class, "registryAccess").set(world.level, registries);
            original = new ItemStack(book ? Items.BOOK : Items.DIAMOND_PICKAXE);
            if (!book) original.setDamageValue(7);
            original.set(DataComponents.CUSTOM_NAME, Component.literal("确认时的物品"));
            world.inventory.setItem(0, original.copy()); world.inventory.setItem(1, new ItemStack(Items.LAPIS_LAZULI, 3));
            world.player.experienceLevel = 35;
            var record = new EnchantTaskRecord("paused-enchantment", 2000, BuiltInRegistries.ITEM.getKey(original.getItem()),
                    new BlockPos(3, 1, 3), 3, 3, 3);
            record.submissionBarrier(() -> true); inventory = EnchantInventory.prepare(world.player, record);
            menu = new EnchantmentMenu(57, world.inventory); world.player.containerMenu = menu;
            menu.getSlot(0).set(original.copy()); menu.getSlot(1).set(new ItemStack(Items.LAPIS_LAZULI, 3)); menu.setData(3, 123);
            for (int i = 0; i < 3; i++) { menu.setData(i, new int[]{5, 15, 30}[i]); menu.setData(4 + i, 0); menu.setData(7 + i, i + 1); }
            MenuPort port = (MenuPort) Proxy.newProxyInstance(MenuPort.class.getClassLoader(), new Class<?>[]{MenuPort.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "ensureVisible" -> true;
                        case "pressButton" -> {
                            buttons++; confirmation = (MenuConfirmation) args[2];
                            var constructor = MenuReceipt.class.getDeclaredConstructor(MenuReceipt.Kind.class, LocalPlayerContext.class,
                                    int.class, int.class, int.class, boolean.class, MenuConfirmation.class);
                            constructor.setAccessible(true);
                            receipt = constructor.newInstance(MenuReceipt.Kind.BUTTON, args[0], menu.containerId, menu.getStateId(), 100, false, confirmation);
                            yield receipt;
                        }
                        case "poll" -> args[1];
                        default -> throw new AssertionError("receipt regression cannot perform extra menu actions: " + method.getName());
                    });
            context = (LocalPlayerContext) Proxy.newProxyInstance(LocalPlayerContext.class.getClassLoader(), new Class<?>[]{LocalPlayerContext.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "player" -> world.player;
                        case "menus" -> port;
                        case "permitsNativeActions" -> true;
                        case "tickRevision" -> tick;
                        case "bodyEpoch", "controlRevision" -> 1L;
                        default -> throw new AssertionError("unexpected receipt context: " + method.getName());
                    });
            flow = new EnchantMenuFlow(world.player, record, menu, inventory, record::prepareSubmission);
            transaction = (EnchantTransaction) field(EnchantMenuFlow.class, "transaction").get(flow);
        }

        @SuppressWarnings({"unchecked", "rawtypes"}) void submit() throws Exception {
            check(!transaction.prepare(context, menu), "the first quote still needs stabilization"); tick += 2;
            check(transaction.prepare(context, menu) && transaction.submit(context, menu), "one stable quote enters the one-shot button path");
            var phase = field(EnchantMenuFlow.class, "phase"); phase.set(flow, Enum.valueOf((Class) phase.getType(), "WAIT_BUTTON"));
        }
        void deliverResult() {
            ItemStack result = original.is(Items.BOOK) ? original.transmuteCopy(Items.ENCHANTED_BOOK) : original.copy(); result.enchant(clue, 3);
            menu.getSlot(0).set(result); menu.getSlot(1).set(ItemStack.EMPTY); world.player.experienceLevel = 32;
        }
        MenuConfirmation.Verdict observeInBackground(boolean synchronizedResult) throws Exception {
            // 明确分开演员推进回执和语义任务 tick，才能复现 MCP 暂停期间前者照常运行的真实时序。
            var verdict = confirmation.observe(context, receipt);
            if (synchronizedResult && verdict != MenuConfirmation.Verdict.PENDING) {
                var finish = MenuReceipt.class.getDeclaredMethod("finish", MenuReceipt.Status.class, String.class); finish.setAccessible(true);
                finish.invoke(receipt, verdict == MenuConfirmation.Verdict.APPLIED ? MenuReceipt.Status.CONFIRMED_APPLIED : MenuReceipt.Status.DIVERGED,
                        "test actor observed synchronized menu facts");
            }
            return verdict;
        }
        @Override public void close() throws Exception { world.close(); }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void rejects(Runnable action, String reason) {
        try { action.run(); throw new AssertionError("expected rejection: " + reason); }
        catch (IllegalStateException expected) { check(expected.getMessage().contains(reason), "rejection retains the missing evidence reason"); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
