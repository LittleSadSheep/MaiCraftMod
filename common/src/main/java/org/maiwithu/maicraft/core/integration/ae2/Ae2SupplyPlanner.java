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

/** Binds semantic acceptable-item groups to concrete AE samples before any effect begins. */
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
     * Bind a complete network-stock plan without considering player inventory capacity.
     * Existing stock is preferred; a declared craftable entry may cover only the shortfall.
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
