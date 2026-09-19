// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.enchant;

import com.mojang.serialization.Lifecycle;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuConfirmation;
import org.maiwithu.maicraft.client.actor.MenuConfirmation.Verdict;
import sun.misc.Unsafe;

/** 用原版菜单同步字段和物品组件模拟服务端分包；只观察报价与结果，不执行附魔按钮或推算随机附魔。 */
public final class EnchantmentQuoteTest {
    private static RegistryAccess registries;
    private static Holder<Enchantment> clue, other;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); prepareRegistry();
        quotesAndSelection(); staleQuotes(); partialResults(); preservedIdentity(); books(); creativeCosts();
        System.out.println("EnchantmentQuoteTest: passed");
    }

    private static void quotesAndSelection() throws Exception {
        var f = new Fixture(false, 35, 8); var quote = f.quote(); var offer = quote.requireChoice(3, 3, 3);
        check(offer.requiredLevel() == 30 && offer.levelsSpent() == 3 && offer.lapisCost() == 3
                        && offer.clue().equals("minecraft:unbreaking") && offer.clueLevel() == 3,
                "三档要求三十级，但实际只扣三级与三份青金石，线索来自同步注册表编号");
        quote.input().setDamageValue(99);
        check(f.menu.getSlot(0).getItem().getDamageValue() == 7 && quote.input().getDamageValue() == 7,
                "调用方修改返回物品不能改变报价或真实菜单中的物品");
        check(!quote.describe().containsKey("seed") && quote.offers().size() == 3, "公开报价只含安全语义，不暴露种子");
        try { quote.offers().clear(); throw new AssertionError("报价列表必须不可变"); } catch (UnsupportedOperationException expected) { }
        rejects(() -> quote.requireChoice(3, 2, 3)); rejects(() -> quote.requireChoice(3, 3, 2));
        rejects(() -> quote.requireChoice(0, 3, 3)); rejects(() -> quote.requireChoice(4, 3, 3));
        rejects(() -> quote.requireChoice(3, -1, 3));
        var lowLevels = new Fixture(false, 29, 8); rejects(() -> lowLevels.quote().requireChoice(3, 3, 3));
        var lowLapis = new Fixture(false, 35, 2); rejects(() -> lowLapis.quote().requireChoice(3, 3, 3));
        f.menu.enchantClue[2] = -1; rejects(() -> f.quote().requireChoice(3, 3, 3));
        f.menu.enchantClue[2] = 999; rejects(() -> f.quote().requireChoice(3, 3, 3));
        f.menu.enchantClue[2] = 0; f.menu.costs[2] = 0; rejects(() -> f.quote().requireChoice(3, 3, 3));
        var enchanted = new Fixture(false, 35, 8); enchanted.menu.getSlot(0).set(enchanted.result());
        rejects(() -> enchanted.quote().requireChoice(3, 3, 3));
    }

    private static void staleQuotes() throws Exception {
        List<Consumer<Fixture>> changes = List.of(
                f -> f.menu.costs[0]++, f -> f.menu.enchantClue[1] = 1, f -> f.menu.levelClue[2] = 2,
                f -> f.menu.setData(3, 456), f -> f.player.experienceLevel--,
                f -> f.menu.getSlot(1).getItem().shrink(1), f -> f.menu.getSlot(0).getItem().setDamageValue(8),
                f -> f.player.getAbilities().instabuild = true,
                f -> f.player.containerMenu = new EnchantmentMenu(f.menu.containerId, f.inventory));
        for (var change : changes) {
            // 即使菜单编号相同，只要会话、种子、任一报价或材料变化，旧选择都不能继续发给原版。
            var f = new Fixture(false, 35, 8); var quote = f.quote(); check(quote.stillMatches(f.player, f.menu), "新报价应匹配当前菜单");
            change.accept(f); check(!quote.stillMatches(f.player, f.menu), "变化后的菜单不能冒充旧报价");
        }
    }

    private static void partialResults() throws Exception {
        for (int[] order : new int[][]{{0,1,2},{0,2,1},{1,0,2},{1,2,0},{2,0,1},{2,1,0}}) {
            var f = new Fixture(false, 35, 8); var quote = f.quote(); var confirmation = quote.confirmation(quote.requireChoice(3, 3, 3));
            check(f.observe(confirmation) == Verdict.PENDING, "仅提交按钮没有结果时必须等待");
            for (int index = 0; index < order.length; index++) {
                // 成品、材料消耗和等级更新任意顺序抵达，前两包均不足以证明整次附魔完成。
                f.update(order[index]);
                check(f.observe(confirmation) == (index == 2 ? Verdict.APPLIED : Verdict.PENDING), "分包部分结果不能被误判成功或失败");
            }
            check(f.observe(confirmation) == Verdict.APPLIED, "重复只读观察不能修改或重试已经确认的结果");
        }
    }

    private static void preservedIdentity() throws Exception {
        List<Consumer<Fixture>> replacements = List.of(
                f -> f.menu.getSlot(0).getItem().setDamageValue(0),
                f -> f.menu.getSlot(0).getItem().remove(DataComponents.CUSTOM_NAME),
                f -> f.menu.getSlot(0).getItem().setCount(2),
                f -> { var wrong = new ItemStack(Items.DIAMOND_SWORD); wrong.enchant(clue, 3); f.menu.getSlot(0).set(wrong); },
                f -> { var wrong = f.original.copy(); wrong.enchant(other, 3); f.menu.getSlot(0).set(wrong); },
                f -> { var wrong = f.original.copy(); wrong.enchant(clue, 2); f.menu.getSlot(0).set(wrong); },
                f -> f.menu.getSlot(1).getItem().set(DataComponents.CUSTOM_NAME, Component.literal("别人的青金石")),
                f -> f.player.experienceLevel = 34,
                f -> f.player.containerMenu = new EnchantmentMenu(f.menu.containerId, f.inventory));
        for (var replace : replacements) {
            var f = new Fixture(false, 35, 8); var quote = f.quote(); var confirmation = quote.confirmation(quote.requireChoice(3, 3, 3));
            f.update(0); f.update(1); f.update(2); replace.accept(f);
            check(f.observe(confirmation) == Verdict.DIVERGED, "换装备、改组件、丢线索或额外消费不能冒充原物品附魔成功");
        }
    }

    private static void books() throws Exception {
        var f = new Fixture(false, 35, 8); f.book(); var quote = f.quote(); var confirmation = quote.confirmation(quote.requireChoice(3, 3, 3));
        f.update(0); f.update(1); f.update(2);
        check(f.menu.getSlot(0).getItem().is(Items.ENCHANTED_BOOK)
                        && !f.menu.getSlot(0).getItem().get(DataComponents.STORED_ENCHANTMENTS).isEmpty()
                        && f.observe(confirmation) == Verdict.APPLIED,
                "书按原版转为附魔书，保留名字，并以储存附魔证明结果");
        var stored = f.menu.getSlot(0).getItem().remove(DataComponents.STORED_ENCHANTMENTS);
        f.menu.getSlot(0).getItem().set(DataComponents.ENCHANTMENTS, stored);
        check(f.observe(confirmation) == Verdict.DIVERGED, "把普通附魔组件塞到书上不能冒充原生附魔书");
    }

    private static void creativeCosts() throws Exception {
        for (int startingLevel : new int[]{0, 2, 35}) {
            var f = new Fixture(true, startingLevel, 0); var quote = f.quote(); int cost = Math.min(startingLevel, 3);
            var offer = quote.requireChoice(3, cost, 0); var confirmation = quote.confirmation(offer);
            check(offer.requiredLevel() == 30 && offer.levelsSpent() == cost && offer.lapisCost() == 0, "创造免等级门槛和青金石，等级仍按原版最低归零");
            if (cost > 0) rejects(() -> quote.requireChoice(3, cost - 1, 0));
            f.update(0);
            check(f.observe(confirmation) == (cost == 0 ? Verdict.APPLIED : Verdict.PENDING), "创造模式不能伪造尚未同步的等级扣费");
            f.player.experienceLevel = startingLevel - cost;
            check(f.observe(confirmation) == Verdict.APPLIED && f.menu.getSlot(1).getItem().isEmpty(), "创造附魔没有消耗或凭空补入青金石");
        }
    }

    private static void prepareRegistry() {
        // 只登记两个确定的测试附魔供原版组件编码和同步编号映射使用，不生成附魔报价或执行随机算法。
        var registry = new MappedRegistry<Enchantment>(Registries.ENCHANTMENT, Lifecycle.stable());
        var definition = Enchantment.definition(HolderSet.direct(Items.DIAMOND_PICKAXE.builtInRegistryHolder()),
                1, 3, Enchantment.constantCost(1), Enchantment.constantCost(30), 1, EquipmentSlotGroup.MAINHAND);
        clue = Registry.registerForHolder(registry, Enchantments.UNBREAKING,
                new Enchantment(Component.literal("耐久"), definition, HolderSet.direct(), DataComponentMap.EMPTY));
        other = Registry.registerForHolder(registry, Enchantments.EFFICIENCY,
                new Enchantment(Component.literal("效率"), definition, HolderSet.direct(), DataComponentMap.EMPTY));
        registry.freeze(); registries = new RegistryAccess.ImmutableRegistryAccess(List.of(registry));
    }

    private static final class Fixture {
        final LocalPlayer player;
        final Inventory inventory;
        final EnchantmentMenu menu;
        final LocalPlayerContext context;
        ItemStack original;
        Fixture(boolean creative, int levels, int lapis) throws Exception {
            // 不启动游戏客户端，只保留菜单读取所需的玩家、世界注册表和背包字段。
            Unsafe memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
            player = (LocalPlayer) memory.allocateInstance(LocalPlayer.class);
            var level = (ClientLevel) memory.allocateInstance(ClientLevel.class);
            field(Level.class, "registryAccess").set(level, registries); field(Entity.class, "level").set(player, level);
            field(LocalPlayer.class, "clientLevel").set(player, level); field(Player.class, "abilities").set(player, new Abilities());
            inventory = new Inventory(player); field(Player.class, "inventory").set(player, inventory);
            player.getAbilities().instabuild = creative; player.experienceLevel = levels;
            menu = new EnchantmentMenu(17, inventory); player.containerMenu = menu;
            original = new ItemStack(Items.DIAMOND_PICKAXE); original.setDamageValue(7);
            original.set(DataComponents.CUSTOM_NAME, Component.literal("自己的工具"));
            menu.getSlot(0).set(original.copy()); menu.getSlot(1).set(lapis == 0 ? ItemStack.EMPTY : new ItemStack(Items.LAPIS_LAZULI, lapis)); sync();
            context = (LocalPlayerContext) Proxy.newProxyInstance(LocalPlayerContext.class.getClassLoader(), new Class<?>[]{LocalPlayerContext.class},
                    (proxy, method, args) -> { if (method.getName().equals("player")) return player; throw new AssertionError("报价确认不能调用动作入口：" + method.getName()); });
        }
        void sync() {
            menu.setData(3, 123);
            for (int i = 0; i < 3; i++) { menu.setData(i, new int[]{5,15,30}[i]); menu.setData(4 + i, 0); menu.setData(7 + i, i + 1); }
        }
        void book() { original = new ItemStack(Items.BOOK); original.set(DataComponents.CUSTOM_NAME, Component.literal("自己的书")); menu.getSlot(0).set(original.copy()); sync(); }
        EnchantmentQuote quote() { return EnchantmentQuote.capture(player, menu); }
        ItemStack result() { ItemStack result = original.is(Items.BOOK) ? original.transmuteCopy(Items.ENCHANTED_BOOK) : original.copy(); result.enchant(clue, 3); return result; }
        void update(int part) {
            if (part == 0) menu.getSlot(0).set(result());
            else if (part == 1) menu.getSlot(1).set(new ItemStack(Items.LAPIS_LAZULI, 5));
            else player.experienceLevel = 32;
        }
        Verdict observe(MenuConfirmation confirmation) { return confirmation.observe(context, null); }
    }
    private static java.lang.reflect.Field field(Class<?> type, String name) throws Exception {
        // 客户端世界引用声明在玩家父类；夹具沿继承链定位真实字段，才能建立供报价读取的同一身体与世界。
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { var value = current.getDeclaredField(name); value.setAccessible(true); return value; }
            catch (NoSuchFieldException missing) { /* 继续检查字段实际所在的父类。 */ }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; } throw new AssertionError("无效报价或预算必须拒绝"); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
