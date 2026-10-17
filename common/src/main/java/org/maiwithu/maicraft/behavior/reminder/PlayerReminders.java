// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.reminder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.level.LightLayer;

import org.maiwithu.maicraft.behavior.survival.FoodPicker;
import org.maiwithu.maicraft.game.player.DamageObserver;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.ReadsFoodValues;
import org.maiwithu.maicraft.game.player.RestStatistics;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;

/**
 * 角色身上的八条提醒：快溺水、着火、口粮不够、装备快坏、背包快满、黑暗里反复挨打、战斗压力大、好几天没睡。
 * 每刻读一遍角色的现状交给各条规则，规则把成立的提醒放上提醒板；提醒只说事实与建议，不暂停任务、不动角色。
 *
 * <p>溺水、着火、挨打每刻看（氧气等不了一秒）；口粮、耐久、背包每秒看一次就认得出持续不足。
 * 角色是不是自动化在控制都照样看：人自己玩的时候 LLM 也该知道。创造、旁观模式不需要这些，进去就清空；
 * 死亡、重生、换世界也清空，上一具身体的提醒不交给新身体。
 */
public final class PlayerReminders implements DamageObserver {

    /** 口粮、耐久、背包隔多少刻看一次。 */
    private static final long SLOW_INTERVAL = 20;
    private static final String ROTTEN_FLESH = "minecraft:rotten_flesh";

    private final ReminderBoard board;
    private final ReadsFoodValues foods;
    private final RestStatistics rest;
    private final DrowningReminder drowning;
    private final BurningReminder burning;
    private final FoodShortageReminder food;
    private final WornGearReminder gear;
    private final FullInventoryReminder space;
    private final DarkAttacksReminder dark;
    private final DarkWithoutTorchReminder unlit;
    private final CombatPressureReminder pressure;
    private final SleeplessReminder sleepless;

    private LocalPlayer player;
    private ClientLevel level;
    private long lastSlowTick = -1;
    /** 这一刻服务器的伤害包：几下来自敌怪、分别是哪种怪、有没有别的伤。 */
    private final List<String> hostileTypesThisTick = new ArrayList<>();
    private boolean otherDamageThisTick;

    public PlayerReminders(TaskEventSink events, ReadsFoodValues foods, RestStatistics rest) {
        this.board = new ReminderBoard(events);
        this.foods = Objects.requireNonNull(foods, "foods");
        this.rest = Objects.requireNonNull(rest, "rest");
        this.drowning = new DrowningReminder(board);
        this.burning = new BurningReminder(board);
        this.food = new FoodShortageReminder(board);
        this.gear = new WornGearReminder(board);
        this.space = new FullInventoryReminder(board);
        this.dark = new DarkAttacksReminder(board);
        this.unlit = new DarkWithoutTorchReminder(board);
        this.pressure = new CombatPressureReminder(board);
        this.sleepless = new SleeplessReminder(board);
    }

    /** 服务器的伤害包：敌怪打的记下是哪种怪，别的伤（摔、烧、淹）只记有过；这一刻结束时一起交给规则。 */
    @Override
    public void hurt(LocalPlayer hurtPlayer, DamageSource source, long gameTick) {
        if (source.getEntity() instanceof Enemy enemy) {
            hostileTypesThisTick.add(EntityType.getKey(source.getEntity().getType()).toString());
        } else {
            otherDamageThisTick = true;
        }
    }

    /** 每刻推进一次：读现状交给各条规则，最后合并通知。 */
    public void tick(PlayerContext context) {
        LocalPlayer now = context == null ? null : context.localPlayer();
        if (!bind(now)) {
            hostileTypesThisTick.clear();
            otherDamageThisTick = false;
            return;
        }
        long tick = level.getGameTime();
        observeLight(tick);
        observeRest(tick);
        drowning.observe(new DrowningReminder.Observation(tick, player.isUnderWater(),
                player.getAirSupply(), player.getMaxAirSupply()));
        burning.observe(new BurningReminder.Observation(tick, player.isOnFire(), player.isInLava(),
                level.dimensionType().ultraWarm()));
        if (lastSlowTick < 0 || tick < lastSlowTick || tick - lastSlowTick >= SLOW_INTERVAL) {
            observeFood(context, tick);
            observeGear(tick);
            space.observe(new FullInventoryReminder.Observation(tick, freeMainSlots(), player.getInventory().items.size()));
            lastSlowTick = tick;
        }
        pressure.observe(combatObservation(tick), hostileTypesThisTick.size(), otherDamageThisTick);
        hostileTypesThisTick.clear();
        otherDamageThisTick = false;
        board.flush();
    }

    /** 此刻还成立的提醒，observe(self) 原样交给 LLM。 */
    public List<Reminder> snapshot() {
        return board.snapshot();
    }

    // 创造、旁观、死了不需要生存提醒，进去就作废；换了身体或世界就重新开始。
    private boolean bind(LocalPlayer now) {
        if (now == null || now.clientLevel == null || now.isDeadOrDying()
                || now.getAbilities().instabuild || now.isSpectator()) {
            reset();
            return false;
        }
        if (player != now || level != now.clientLevel) {
            reset();
            player = now;
            level = now.clientLevel;
        }
        return true;
    }

    private void reset() {
        board.clear();
        drowning.clear();
        burning.clear();
        food.clear();
        gear.clear();
        space.clear();
        dark.clear();
        unlit.clear();
        pressure.clear();
        rest.clear();
        lastSlowTick = -1;
        player = null;
        level = null;
    }

    // 只读脚下已加载格的亮度；读不到就是不知道，不能拿零冒充黑暗。每一下敌怪的命中各算一次。
    private void observeLight(long tick) {
        BlockPos feet = player.blockPosition();
        int block = -1;
        int sky = -1;
        int local = -1;
        if (level.isLoaded(feet)) {
            block = level.getBrightness(LightLayer.BLOCK, feet);
            sky = level.getBrightness(LightLayer.SKY, feet);
            local = level.getMaxLocalRawBrightness(feet);
        }
        var observation = new DarkAttacksReminder.Observation(tick, level.dimension().location().toString(),
                feet, block, sky, local);
        if (hostileTypesThisTick.isEmpty()) {
            dark.observe(observation, null);
        }
        for (String type : hostileTypesThisTick) {
            dark.observe(observation, type);
        }
        unlit.observe(new DarkWithoutTorchReminder.Observation(tick, block, sky, carriedTorches()));
    }

    // 身上（主背包和副手）的火把数：自动补光就插这个。
    private int carriedTorches() {
        int total = player.getOffhandItem().is(Items.TORCH) ? player.getOffhandItem().getCount() : 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(Items.TORCH)) total += stack.getCount();
        }
        return total;
    }

    // 个人统计每三十秒请求一次；有效的休息计数才交给规则，没有就撤下这一条。
    private void observeRest(long tick) {
        rest.tick(player);
        OptionalInt sinceRest = rest.ticksSinceRest(tick);
        if (sinceRest.isEmpty()) {
            sleepless.unavailable(tick, player.isSleeping() ? "睡下了" : "还没拿到有效的休息统计");
            return;
        }
        var dimension = level.dimensionType();
        sleepless.observe(new SleeplessReminder.Observation(tick, sinceRest.getAsInt(), rest.observedAt(),
                level.getDayTime(), dimension.bedWorks(), dimension.natural()));
    }

    // 口粮：身上（主背包）普通食物的件数与营养合计，腐肉单独数；普通食物按挑吃的同一套判断。
    private void observeFood(PlayerContext context, long tick) {
        long count = 0;
        long nutrition = 0;
        long flesh = 0;
        for (var stack : context.backpack().stacks()) {
            if (stack.itemId().equals(ROTTEN_FLESH)) flesh += stack.count();
            var value = foods.of(stack.itemId());
            if (value.isPresent() && FoodPicker.plain(value.get())) {
                count += stack.count();
                nutrition += (long) stack.count() * value.get().nutrition();
            }
        }
        food.observe(new FoodShortageReminder.Observation(tick, player.getFoodData().getFoodLevel(),
                count, nutrition, flesh));
    }

    // 耐久只看主手与身上四件盔甲；背包里的备件不在这里数。
    private void observeGear(long tick) {
        List<WornGearReminder.SlotFact> slots = new ArrayList<>();
        addWorn(slots, "mainhand", player.getMainHandItem());
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
            addWorn(slots, slot.name().toLowerCase(Locale.ROOT), player.getItemBySlot(slot));
        }
        gear.observe(new WornGearReminder.Observation(tick, slots));
    }

    private static void addWorn(List<WornGearReminder.SlotFact> slots, String slot, ItemStack stack) {
        if (stack.isEmpty() || !stack.isDamageableItem()) return;
        slots.add(new WornGearReminder.SlotFact(slot, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                stack.getMaxDamage() - stack.getDamageValue(), stack.getMaxDamage()));
    }

    // 只数主背包三十六格的空格。
    private int freeMainSlots() {
        int free = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.isEmpty()) free++;
        }
        return free;
    }

    // 战斗压力的现状：血量、护甲、身上有没有能射的远程武器（弓加箭，或装好的弩），盔甲穿了什么。
    private CombatPressureReminder.Observation combatObservation(long tick) {
        boolean ranged = false;
        boolean loaded = false;
        long arrows = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) continue;
            if (stack.getItem() instanceof ArrowItem) arrows += stack.getCount();
            if (stack.getItem() instanceof ProjectileWeaponItem) {
                ranged = true;
                var projectiles = stack.get(DataComponents.CHARGED_PROJECTILES);
                loaded |= projectiles != null && !projectiles.isEmpty();
            }
        }
        List<Map<String, Object>> armor = new ArrayList<>();
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
            ItemStack stack = player.getItemBySlot(slot);
            if (stack.isEmpty()) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("slot", slot.name().toLowerCase(Locale.ROOT));
            item.put("item_id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            armor.add(item);
        }
        Map<String, Object> equipment = new LinkedHashMap<>();
        equipment.put("equipped_armor", armor);
        equipment.put("ranged_weapon_carried", ranged);
        equipment.put("arrows_carried", arrows);
        return new CombatPressureReminder.Observation(tick, player.getHealth(), player.getMaxHealth(),
                player.getArmorValue(), ranged && (arrows > 0 || loaded), equipment);
    }
}
