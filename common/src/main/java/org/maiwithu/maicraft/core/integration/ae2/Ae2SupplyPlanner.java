// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ae2;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * 只计划、不点击：按请求选择网络里的具体物品，并预分配背包空间；后续执行必须继续符合这份选择和已确认数量。
 * 单一品种组选一种物品，混合组可凑数量；现有可合并的背包堆先用，空格留给其余物品。
 */
final class Ae2SupplyPlanner {
    record Candidate(ResourceLocation itemId, ItemStack sample, long storedAmount,
                     boolean craftable, long stableSerial) {
        Candidate {
            sample = sample.copyWithCount(1);
            storedAmount = Math.max(0L, storedAmount);
        }
    }

    static final class Allocation {
        private final ResourceLocation itemId;
        private final ItemStack sample;
        private final int count;
        private final boolean craftingAllowed;
        private int confirmedCount;

        Allocation(ResourceLocation itemId, ItemStack sample, int count, boolean craftingAllowed) {
            this.itemId = itemId;
            this.sample = sample.copyWithCount(1);
            this.count = count;
            this.craftingAllowed = craftingAllowed;
        }

        ResourceLocation itemId() { return itemId; }
        ItemStack sample() { return sample; }
        int count() { return count; }
        boolean craftingAllowed() { return craftingAllowed; }
        int confirmedCount() { return confirmedCount; }
        int remaining() { return count - confirmedCount; }

        void confirm(int amount) {
            if (amount < 1 || confirmedCount > count - amount) {
                throw new IllegalStateException("confirmed AE2 allocation exceeds its approved count");
            }
            confirmedCount += amount;
        }
    }

    record PlannedGroup(Ae2ResourceSupply.Group group, List<Allocation> allocations) {
        PlannedGroup {
            allocations = List.copyOf(allocations);
        }

        int confirmedCount() {
            return allocations.stream().mapToInt(Allocation::confirmedCount).sum();
        }
    }

    record Plan(List<PlannedGroup> groups) {
        Plan { groups = List.copyOf(groups); }
    }

    record Failure(String code, String message, Ae2ResourceSupply.Group group) {}

    record Result(Plan plan, Failure failure) {
        static Result success(Plan plan) { return new Result(plan, null); }
        static Result failure(Failure failure) { return new Result(null, failure); }
    }

    private record SlotOption(int candidateIndex, int capacity) {}

    private static final class InventorySlot {
        private ItemStack sample;
        private int count;

        InventorySlot(ItemStack sample, int count) {
            this.sample = sample == null ? null : sample.copyWithCount(1);
            this.count = count;
        }

        InventorySlot copy() { return new InventorySlot(sample, count); }
    }

    /** Exact capacity simulator; empty slots become bound to a concrete component-exact sample. */
    // 在一份背包副本上试放物品；只扣计划容量，不改真实背包，试选失败的候选可丢掉这份副本。
    private static final class Allocator {
        private final List<InventorySlot> slots;

        private Allocator(List<InventorySlot> slots) {
            this.slots = slots;
        }

        static Allocator capture(LocalPlayer player, Set<Integer> reservedSlots) {
            List<InventorySlot> slots = new ArrayList<>();
            for (int index = 0; index <= 35; index++) {
                if (reservedSlots.contains(index)) continue;
                ItemStack stack = player.getInventory().getItem(index);
                slots.add(stack.isEmpty()
                        ? new InventorySlot(null, 0)
                        : new InventorySlot(stack, stack.getCount()));
            }
            return new Allocator(slots);
        }

        Allocator fork() {
            return new Allocator(slots.stream().map(InventorySlot::copy)
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new)));
        }

        int unboundEmptySlots() {
            return (int) slots.stream().filter(slot -> slot.sample == null).count();
        }

        int boundCapacity(ItemStack sample) {
            int total = 0;
            for (InventorySlot slot : slots) {
                if (slot.sample != null && same(slot.sample, sample)) {
                    total = saturatingIntAdd(total,
                            Math.max(0, slot.sample.getMaxStackSize() - slot.count));
                }
            }
            return total;
        }

        int totalCapacity(ItemStack sample) {
            long capacity = (long) boundCapacity(sample)
                    + (long) unboundEmptySlots() * Math.max(1, sample.getMaxStackSize());
            return (int) Math.min(Integer.MAX_VALUE, capacity);
        }

        int allocateBound(ItemStack sample, int requested) {
            int normalized = Math.max(0, requested);
            int remaining = normalized;
            for (InventorySlot slot : slots) {
                if (remaining <= 0) break;
                if (slot.sample != null && same(slot.sample, sample)) {
                    int capacity = Math.max(0, slot.sample.getMaxStackSize() - slot.count);
                    int moved = Math.min(remaining, capacity);
                    slot.count += moved;
                    remaining -= moved;
                }
            }
            return normalized - remaining;
        }

        int allocate(ItemStack sample, int requested) {
            int normalized = Math.max(0, requested);
            int remaining = normalized - allocateBound(sample, normalized);
            if (remaining <= 0) return normalized;
            int stackSize = Math.max(1, sample.getMaxStackSize());
            for (InventorySlot slot : slots) {
                if (remaining <= 0) break;
                if (slot.sample == null) {
                    int moved = Math.min(remaining, stackSize);
                    slot.sample = sample.copyWithCount(1);
                    slot.count = moved;
                    remaining -= moved;
                }
            }
            return normalized - remaining;
        }
    }

    private Ae2SupplyPlanner() {}

    /**
     * 只准备网络库存时不占用背包格；先选现有库存，缺少部分在允许合成且网络提供样板时列为待制作。
     */
    static Result prepare(
            Ae2ResourceSupply.Request request,
            List<Ae2ReflectionBridge.Entry> entries) {
        List<PlannedGroup> groups = new ArrayList<>();
        for (Ae2ResourceSupply.Group group : request.groups()) {
            List<Candidate> candidates = candidates(group, entries);
            if (candidates.isEmpty()) return unavailableFailure(group, request);

            if (group.selectionMode() == Ae2ResourceSupply.SelectionMode.SINGLE_VARIANT) {
                Candidate selected = candidates.stream()
                        .filter(candidate -> candidate.storedAmount() >= group.count()
                                || request.allowCrafting() && candidate.craftable())
                        .sorted(Comparator
                                .comparingInt((Candidate candidate) ->
                                        candidate.storedAmount() >= group.count() ? 0 : 1)
                                .thenComparing(Comparator.comparingLong(
                                        Candidate::storedAmount).reversed())
                                .thenComparing(candidate -> candidate.itemId().toString())
                                .thenComparingLong(Candidate::stableSerial))
                        .findFirst().orElse(null);
                if (selected == null) return unavailableFailure(group, request);
                groups.add(new PlannedGroup(group, List.of(new Allocation(
                        selected.itemId(), selected.sample(), group.count(),
                        request.allowCrafting() && selected.craftable()))));
                continue;
            }

            int remaining = group.count();
            Map<Candidate, Integer> counts = new LinkedHashMap<>();
            List<Candidate> stocked = candidates.stream()
                    .filter(candidate -> candidate.storedAmount() > 0)
                    .sorted(Comparator.comparingLong(Candidate::storedAmount).reversed()
                            .thenComparing(candidate -> candidate.itemId().toString())
                            .thenComparingLong(Candidate::stableSerial))
                    .toList();
            for (Candidate candidate : stocked) {
                if (remaining <= 0) break;
                int allocated = (int) Math.min(remaining, candidate.storedAmount());
                if (allocated > 0) counts.put(candidate, allocated);
                remaining -= allocated;
            }
            if (remaining > 0) {
                Candidate craftable = request.allowCrafting()
                        ? candidates.stream().filter(Candidate::craftable)
                                .sorted(Comparator.comparing(
                                                (Candidate candidate) -> candidate.itemId().toString())
                                        .thenComparingLong(Candidate::stableSerial))
                                .findFirst().orElse(null)
                        : null;
                if (craftable == null) return unavailableFailure(group, request);
                counts.merge(craftable, remaining, Integer::sum);
            }
            List<Allocation> allocations = new ArrayList<>();
            counts.forEach((candidate, count) -> allocations.add(new Allocation(
                    candidate.itemId(), candidate.sample(), count,
                    request.allowCrafting() && candidate.craftable())));
            groups.add(new PlannedGroup(group, allocations));
        }
        return Result.success(new Plan(groups));
    }

    static Result build(
            LocalPlayer player,
            Ae2ResourceSupply.Request request,
            List<Ae2ReflectionBridge.Entry> entries,
            Set<Integer> reservedSlots) {
        Allocator allocator = Allocator.capture(player, reservedSlots);
        List<PlannedGroup> groups = new ArrayList<>();
        for (Ae2ResourceSupply.Group group : request.groups()) {
            List<Candidate> candidates = candidates(group, entries);
            if (group.selectionMode() == Ae2ResourceSupply.SelectionMode.SINGLE_VARIANT) {
                Candidate best = null;
                Allocator bestAllocator = null;
                int bestCraftMissing = Integer.MAX_VALUE;
                int bestEmptyUsed = Integer.MAX_VALUE;
                boolean supplyable = false;
                for (Candidate candidate : candidates) {
                    if (supplyLimit(candidate, group.count(), request.allowCrafting()) < group.count()) {
                        continue;
                    }
                    supplyable = true;
                    Allocator trial = allocator.fork();
                    int emptyBefore = trial.unboundEmptySlots();
                    if (trial.allocate(candidate.sample(), group.count()) != group.count()) continue;
                    int emptyUsed = emptyBefore - trial.unboundEmptySlots();
                    int stocked = (int) Math.min(group.count(), candidate.storedAmount());
                    int craftMissing = Math.max(0, group.count() - stocked);
                    if (better(candidate, craftMissing, emptyUsed,
                            best, bestCraftMissing, bestEmptyUsed)) {
                        best = candidate;
                        bestAllocator = trial;
                        bestCraftMissing = craftMissing;
                        bestEmptyUsed = emptyUsed;
                    }
                }
                if (best == null || bestAllocator == null) {
                    return supplyable ? capacityFailure(group) : unavailableFailure(group, request);
                }
                allocator = bestAllocator;
                groups.add(new PlannedGroup(group, List.of(new Allocation(
                        best.itemId(), best.sample(), group.count(),
                        request.allowCrafting() && best.craftable()))));
                continue;
            }

            long[] storedLimits = new long[candidates.size()];
            long directStored = 0L;
            for (int i = 0; i < candidates.size(); i++) {
                storedLimits[i] = Math.min(group.count(), candidates.get(i).storedAmount());
                directStored = saturatingAdd(directStored, storedLimits[i]);
            }
            long[] limits = storedLimits.clone();
            // 当前只有现货总数不够时才放开合成；现货虽够但装不下时，不会再考虑能堆叠的可合成替代品。
            if (directStored < group.count()) {
                for (int i = 0; i < candidates.size(); i++) {
                    limits[i] = supplyLimit(candidates.get(i), group.count(), request.allowCrafting());
                }
            }
            long totalSupply = 0L;
            for (long limit : limits) totalSupply = saturatingAdd(totalSupply, limit);
            if (totalSupply < group.count()) return unavailableFailure(group, request);

            int[] allocationCounts = new int[candidates.size()];
            int remaining = group.count();
            List<Integer> boundOrder = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) boundOrder.add(i);
            Allocator allocationView = allocator;
            boundOrder.sort(Comparator
                    .<Integer>comparingLong(index -> Math.min(
                            allocationView.boundCapacity(candidates.get(index).sample()), limits[index]))
                    .reversed()
                    .thenComparing(Comparator.<Integer>comparingLong(
                            index -> candidates.get(index).storedAmount()).reversed())
                    .thenComparing(Comparator.<Integer>comparingInt(
                            index -> candidates.get(index).sample().getMaxStackSize()).reversed())
                    .thenComparing(index -> candidates.get(index).itemId().toString())
                    .thenComparingLong(index -> candidates.get(index).stableSerial()));
            for (int index : boundOrder) {
                if (remaining <= 0) break;
                int requested = (int) Math.min(
                        Math.min((long) remaining, limits[index]),
                        allocator.boundCapacity(candidates.get(index).sample()));
                int moved = allocator.allocateBound(candidates.get(index).sample(), requested);
                allocationCounts[index] += moved;
                limits[index] -= moved;
                remaining -= moved;
            }

            List<SlotOption> options = new ArrayList<>();
            int emptySlots = allocator.unboundEmptySlots();
            for (int index = 0; index < candidates.size(); index++) {
                long supply = Math.min(limits[index], remaining);
                int stackSize = Math.max(1, candidates.get(index).sample().getMaxStackSize());
                int optionsForCandidate = 0;
                while (supply > 0 && optionsForCandidate < emptySlots) {
                    int capacity = (int) Math.min(stackSize, supply);
                    options.add(new SlotOption(index, capacity));
                    supply -= capacity;
                    optionsForCandidate++;
                }
            }
            options.sort(Comparator.comparingInt(SlotOption::capacity).reversed()
                    .thenComparing(Comparator.<SlotOption>comparingLong(
                            option -> candidates.get(option.candidateIndex()).storedAmount()).reversed())
                    .thenComparingInt(option -> candidates.get(option.candidateIndex()).craftable() ? 1 : 0)
                    .thenComparing(option -> candidates.get(option.candidateIndex()).itemId().toString())
                    .thenComparingLong(option -> candidates.get(option.candidateIndex()).stableSerial()));
            for (SlotOption option : options) {
                if (remaining <= 0 || allocator.unboundEmptySlots() <= 0) break;
                int index = option.candidateIndex();
                int requested = Math.min(remaining, option.capacity());
                int moved = allocator.allocate(candidates.get(index).sample(), requested);
                allocationCounts[index] += moved;
                limits[index] -= moved;
                remaining -= moved;
            }
            if (remaining > 0) return capacityFailure(group);
            List<Allocation> allocations = new ArrayList<>();
            for (int index = 0; index < candidates.size(); index++) {
                if (allocationCounts[index] <= 0) continue;
                Candidate candidate = candidates.get(index);
                allocations.add(new Allocation(
                        candidate.itemId(), candidate.sample(), allocationCounts[index],
                        request.allowCrafting() && candidate.craftable()));
            }
            groups.add(new PlannedGroup(group, allocations));
        }
        return Result.success(new Plan(groups));
    }

    static List<Ae2ReflectionBridge.Entry> matchingEntries(
            List<Ae2ReflectionBridge.Entry> entries, Allocation allocation) {
        return entries.stream().filter(entry -> entry.itemId().equals(allocation.itemId())
                        && same(entry.sample(), allocation.sample())).toList();
    }

    // 每次准备取下一批前，复查已确认增量、剩余网络库存和预留容量；一处不符就说明原计划已变。
    static String issue(
            Plan plan,
            LocalPlayer player,
            List<Ae2ReflectionBridge.Entry> entries,
            Set<Integer> reservedSlots,
            java.util.function.ToIntFunction<Ae2ResourceSupply.Group> groupProgress) {
        Allocator allocator = Allocator.capture(player, reservedSlots);
        for (PlannedGroup group : plan.groups()) {
            if (groupProgress.applyAsInt(group.group()) != group.confirmedCount()) {
                return "inventory_delta_changed";
            }
            for (Allocation allocation : group.allocations()) {
                int remaining = allocation.remaining();
                if (remaining <= 0) continue;
                List<Ae2ReflectionBridge.Entry> matching = matchingEntries(entries, allocation);
                long stored = matching.stream().mapToLong(Ae2ReflectionBridge.Entry::storedAmount)
                        .reduce(0L, Ae2SupplyPlanner::saturatingAdd);
                boolean craftable = allocation.craftingAllowed()
                        && matching.stream().anyMatch(Ae2ReflectionBridge.Entry::craftable);
                if (stored < remaining && !craftable) return "network_supply_changed";
                if (allocator.allocate(allocation.sample(), remaining) != remaining) {
                    return "inventory_capacity_changed";
                }
            }
        }
        return null;
    }

    private static boolean better(
            Candidate candidate, int craftMissing, int emptyUsed,
            Candidate best, int bestCraftMissing, int bestEmptyUsed) {
        if (best == null) return true;
        if (craftMissing != bestCraftMissing) return craftMissing < bestCraftMissing;
        if (emptyUsed != bestEmptyUsed) return emptyUsed < bestEmptyUsed;
        if (candidate.storedAmount() != best.storedAmount()) {
            return candidate.storedAmount() > best.storedAmount();
        }
        int byId = candidate.itemId().toString().compareTo(best.itemId().toString());
        return byId < 0 || (byId == 0 && candidate.stableSerial() < best.stableSerial());
    }

    // 当前只选没有额外组件变化、单叠上限在一到六十四之间的物品；带自定义数据的网络条目会在这里被排除。
    private static List<Candidate> candidates(
            Ae2ResourceSupply.Group group, List<Ae2ReflectionBridge.Entry> entries) {
        List<Candidate> result = new ArrayList<>();
        for (Ae2ReflectionBridge.Entry entry : entries) {
            if (!group.acceptableItemIds().contains(entry.itemId())
                    || !entry.sample().getComponentsPatch().isEmpty()
                    || entry.sample().getMaxStackSize() < 1
                    || entry.sample().getMaxStackSize() > 64) {
                continue;
            }
            int existingIndex = -1;
            for (int i = 0; i < result.size(); i++) {
                Candidate existing = result.get(i);
                if (existing.itemId().equals(entry.itemId()) && same(existing.sample(), entry.sample())) {
                    existingIndex = i;
                    break;
                }
            }
            if (existingIndex < 0) {
                result.add(new Candidate(entry.itemId(), entry.sample(), entry.storedAmount(),
                        entry.craftable(), entry.serial()));
            } else {
                Candidate existing = result.get(existingIndex);
                result.set(existingIndex, new Candidate(
                        existing.itemId(), existing.sample(),
                        saturatingAdd(existing.storedAmount(), entry.storedAmount()),
                        existing.craftable() || entry.craftable(),
                        Math.min(existing.stableSerial(), entry.serial())));
            }
        }
        result.sort(Comparator.comparing((Candidate value) -> value.itemId().toString())
                .thenComparingLong(Candidate::stableSerial));
        return List.copyOf(result);
    }

    private static long supplyLimit(Candidate candidate, int requested, boolean allowCrafting) {
        return allowCrafting && candidate.craftable()
                ? requested : Math.min(requested, candidate.storedAmount());
    }

    private static Result unavailableFailure(
            Ae2ResourceSupply.Group group, Ae2ResourceSupply.Request request) {
        String code = request.allowCrafting()
                ? "crafting_pattern_missing" : "network_stock_insufficient";
        return Result.failure(new Failure(
                code,
                "the AE2 network cannot fully supply one bound plan for " + group.itemId(),
                group));
    }

    private static Result capacityFailure(Ae2ResourceSupply.Group group) {
        return Result.failure(new Failure(
                "inventory_full",
                "the player inventory cannot hold the concrete AE2 plan for " + group.itemId(),
                group));
    }

    private static boolean same(ItemStack left, ItemStack right) {
        return left.getCount() == right.getCount()
                && ItemStack.isSameItemSameComponents(left, right);
    }

    private static long saturatingAdd(long left, long right) {
        return right > 0 && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static int saturatingIntAdd(int left, int right) {
        long result = (long) left + right;
        return (int) Math.min(Integer.MAX_VALUE, result);
    }
}
