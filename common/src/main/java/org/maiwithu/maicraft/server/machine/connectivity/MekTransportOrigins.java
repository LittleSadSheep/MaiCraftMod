// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.connectivity;

import java.lang.ref.WeakReference;
import java.lang.ref.ReferenceQueue;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.server.machine.NativeApi;
import org.maiwithu.maicraft.server.machine.ProductionEventJournal.OrderingMarker;
import org.maiwithu.maicraft.server.machine.ServerProductionEvents;

/** Runtime-only provenance from successful native extraction, never from a sorter's facing at delivery. */
public final class MekTransportOrigins {
    private static final int LIMIT = 4096;
    private static final String TRANSMITTER = "mekanism.common.content.network.transmitter.Transmitter";
    private static final String STACK = "mekanism.common.content.transporter.TransporterStack";
    private static final String RESPONSE = "mekanism.common.lib.inventory.TransitRequest$TransitResponse";
    private static final WeakKeys<Source> SOURCES = new WeakKeys<>();
    private static final WeakKeys<Pending> PENDING = new WeakKeys<>();
    private static final WeakKeys<Tracked> ORIGINS = new WeakKeys<>();
    private static final ThreadLocal<ArrayDeque<Emission>> EMITTING = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<ArrayDeque<Use>> USING = ThreadLocal.withInitial(ArrayDeque::new);

    private record Source(WeakReference<ServerLevel> level, BlockPos position, WeakReference<Object> handler, long tick) {
        boolean current() {
            ServerLevel actual = level.get();
            return actual != null && actual.getServer().isSameThread() && actual.getGameTime() == tick && handler.get() != null;
        }
    }
    private record Pending(Source source, WeakReference<Object> transported, WeakReference<Object> response, ItemStack expected) {}
    private record Tracked(WeakReference<ServerLevel> level, BlockPos source, ItemStack identity, int remaining,
                           OrderingMarker extraction) {}

    public record Origin(BlockPos source, ItemStack identity, int remaining, OrderingMarker extraction) {
        public Origin { source = source.immutable(); identity = identity.copy(); }
        @Override public ItemStack identity() { return identity.copy(); }
    }
    public static final class Emission {
        private final Source source;
        private WeakReference<Object> transported;
        private int created;
        private boolean invalid, closed;
        private Emission(Source source) { this.source = source; }
    }
    public static final class Use {
        private final Pending pending;
        private int extracted;
        private boolean invalid, closed;
        private Use(Pending pending) { this.pending = pending; }
    }

    // TransitResponse has a mutable content-based hashCode. Native objects must be keyed by identity.
    private static final class IdentityRef extends WeakReference<Object> {
        private final int hash;
        private IdentityRef(Object value, ReferenceQueue<Object> queue) {
            super(value, queue); hash = System.identityHashCode(value);
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return this == other || get() != null && other instanceof IdentityRef ref && get() == ref.get();
        }
    }
    private static final class WeakKeys<T> {
        private final ReferenceQueue<Object> queue = new ReferenceQueue<>();
        private final Map<IdentityRef, T> values = new HashMap<>();
        private void clean() { for (var ref = queue.poll(); ref != null; ref = queue.poll()) values.remove(ref); }
        T get(Object key) { clean(); return key == null ? null : values.get(new IdentityRef(key, null)); }
        T remove(Object key) { clean(); return key == null ? null : values.remove(new IdentityRef(key, null)); }
        void put(Object key, T value) { clean(); values.put(new IdentityRef(key, queue), value); }
        int size() { clean(); return values.size(); }
        boolean containsKey(Object key) { return get(key) != null; }
    }

    private MekTransportOrigins() {}

    public static synchronized void noteSource(Object owner, ServerLevel level, BlockPos position, Object handler) {
        try {
            SOURCES.remove(owner);
            if (owner != null && level != null && position != null && handler != null
                    && level.getServer().isSameThread() && SOURCES.size() < LIMIT) {
                SOURCES.put(owner, new Source(new WeakReference<>(level), position.immutable(),
                        new WeakReference<>(handler), level.getGameTime()));
            }
        } catch (RuntimeException | LinkageError ignored) { SOURCES.remove(owner); }
    }

    public static synchronized Emission beginEmission(Object owner) {
        Emission scope = new Emission(SOURCES.get(owner));
        // Invalid nested scopes still mask their parent, so a child can never donate a created stack.
        if (EMITTING.get().size() >= 32) EMITTING.get().clear();
        EMITTING.get().push(scope);
        return scope;
    }

    public static synchronized void created(Object transmitter, Object transported) {
        Emission scope = EMITTING.get().peek();
        if (scope == null) return;
        try {
            if (scope.source == null || !scope.source.current() || transported == null
                    || NativeApi.call(transmitter, TRANSMITTER, "getLevel") != scope.source.level.get()) {
                scope.invalid = true;
            } else {
                scope.created++;
                scope.transported = new WeakReference<>(transported);
            }
        } catch (RuntimeException | LinkageError ignored) { scope.invalid = true; }
    }

    /** Always call in finally; pass null when native emission threw. */
    public static synchronized void finishEmission(Emission scope, Object response) {
        if (scope == null || scope.closed) return;
        ArrayDeque<Emission> stack = EMITTING.get();
        try {
            if (stack.peek() != scope || scope.invalid || scope.created != 1 || scope.source == null
                    || !scope.source.current() || response == null || PENDING.size() >= LIMIT) return;
            ItemStack expected = ((ItemStack) NativeApi.call(response, RESPONSE, "getStack")).copy();
            Object transported = scope.transported.get();
            if (expected.isEmpty() || transported == null || !matchesCargo(transported, expected)) return;
            PENDING.put(response, new Pending(scope.source, scope.transported, new WeakReference<>(response), expected));
        } catch (RuntimeException | LinkageError ignored) {
            // Observation failure never changes native emission behavior.
        } finally {
            scope.closed = true;
            if (stack.peek() == scope) stack.pop(); else stack.clear();
            if (stack.isEmpty()) EMITTING.remove();
        }
    }

    public static synchronized Use beginUse(Object response) {
        Pending pending = PENDING.remove(response);
        if (pending != null && pending.response.get() != response) pending = null;
        Use scope = new Use(pending);
        if (USING.get().size() >= 32) USING.get().clear();
        USING.get().push(scope);
        return scope;
    }

    public static synchronized void observedExtraction(Object handler, int requested, ItemStack result, boolean simulate) {
        Use scope = USING.get().peek();
        if (scope == null || scope.pending == null || simulate) return;
        try {
            Pending pending = scope.pending;
            if (!pending.source.current() || handler != pending.source.handler.get() || requested < 0 || result == null
                    || result.getCount() > requested || !result.isEmpty()
                    && !ItemStack.isSameItemSameComponents(result, pending.expected)) {
                scope.invalid = true;
                return;
            }
            long total = (long) scope.extracted + result.getCount();
            if (total > pending.expected.getCount()) scope.invalid = true;
            else scope.extracted = (int) total;
        } catch (RuntimeException | LinkageError ignored) { scope.invalid = true; }
    }

    /** completed means native useAll returned normally; its return stack is not an extraction receipt. */
    public static synchronized void finishUse(Use scope, boolean completed) {
        if (scope == null || scope.closed) return;
        ArrayDeque<Use> stack = USING.get();
        try {
            Pending pending = scope.pending;
            if (stack.peek() != scope || !completed || scope.invalid || pending == null || !pending.source.current()
                    || scope.extracted != pending.expected.getCount() || scope.extracted <= 0 || ORIGINS.size() >= LIMIT) return;
            Object transported = pending.transported.get();
            if (transported == null || ORIGINS.containsKey(transported) || !matchesCargo(transported, pending.expected)) return;
            ServerLevel level = pending.source.level.get();
            if (level == null) return;
            // Freeze ordering at the verified native extraction, not when this cargo eventually arrives.
            OrderingMarker extraction = ServerProductionEvents.markExtraction(level);
            ORIGINS.put(transported, new Tracked(pending.source.level, pending.source.position,
                    pending.expected.copyWithCount(1), scope.extracted, extraction));
        } catch (RuntimeException | LinkageError ignored) {
            // A partial or unobserved extraction leaves this cargo unattributed.
        } finally {
            scope.closed = true;
            if (stack.peek() == scope) stack.pop(); else stack.clear();
            if (stack.isEmpty()) USING.remove();
        }
    }

    public static synchronized Origin origin(Object transported, ServerLevel level) {
        try {
            Tracked tracked = ORIGINS.get(transported);
            return tracked != null && tracked.level.get() == level && level.getServer().isSameThread()
                    ? new Origin(tracked.source, tracked.identity, tracked.remaining, tracked.extraction) : null;
        } catch (RuntimeException | LinkageError ignored) { return null; }
    }

    public static synchronized boolean consume(Object transported, ServerLevel level, ItemStack delivered) {
        try {
            Tracked tracked = ORIGINS.get(transported);
            if (tracked == null || tracked.level.get() != level || !level.getServer().isSameThread()) return false;
            if (delivered == null || delivered.isEmpty() || delivered.getCount() > tracked.remaining
                    || !ItemStack.isSameItemSameComponents(delivered, tracked.identity)) {
                ORIGINS.remove(transported);
                return false;
            }
            int remaining = tracked.remaining - delivered.getCount();
            if (remaining == 0) ORIGINS.remove(transported);
            else ORIGINS.put(transported, new Tracked(tracked.level, tracked.source, tracked.identity, remaining, tracked.extraction));
            return true;
        } catch (RuntimeException | LinkageError ignored) { return false; }
    }

    private static boolean matchesCargo(Object transported, ItemStack expected) {
        ItemStack cargo = (ItemStack) NativeApi.field(transported, STACK, "itemStack");
        return cargo.getCount() == expected.getCount() && ItemStack.isSameItemSameComponents(cargo, expected);
    }
}
