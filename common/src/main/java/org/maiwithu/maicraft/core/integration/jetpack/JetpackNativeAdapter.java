// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.jetpack;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;

/** Optional Create Jetpack 5.1.2 / FlightLib 3.2.1 bridge, audited against the installed jars.
 * Never writes attachments, fuel, position or velocity. The two switches use ControlSender's
 * own packet + client prediction path; their receipts confirm observed client mode, not server ACK.
 */
public final class JetpackNativeAdapter {
    private static final String FLIGHT = "com.possible_triangle.flightlib.";
    private static final String CONFIG = "com.possible_triangle.create_jetpack.config.Configs";
    private static final String TANK = "com.simibubi.create.content.equipment.armor.BacktankUtil";

    public record Snapshot(boolean known, String reason, String item, boolean active, boolean hover,
                           int air, int fuelTicks, double horizontal, double vertical,
                           double acceleration, double hoverDescent, double gravity) {
        static Snapshot unavailable(String reason) {
            return new Snapshot(false, reason, "", false, false, 0, 0, 0, 0, 0, 0, 0);
        }
        public boolean controllable() {
            return known && fuelTicks > 0 && Double.isFinite(horizontal) && horizontal > 0
                    && horizontal <= 0.1 && Double.isFinite(vertical) && vertical > gravity && vertical <= 1
                    && Double.isFinite(acceleration) && acceleration > gravity
                    && Double.isFinite(hoverDescent) && hoverDescent <= 0 && hoverDescent >= -0.2
                    && Double.isFinite(gravity) && gravity > 0;
        }
    }

    public static Snapshot inspect(LocalPlayerContext ctx) {
        ctx.requireCurrent();
        return observe(ctx.player());
    }
    public static Snapshot observe(net.minecraft.client.player.LocalPlayer player) {
        if (!net.minecraft.client.Minecraft.getInstance().isSameThread()) throw new IllegalStateException("client-thread jetpack observation required");
        if (player == null) return Snapshot.unavailable("local player unavailable");
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        String id = BuiltInRegistries.ITEM.getKey(chest.getItem()).toString();
        if (!id.equals("create_jetpack:netherite_jetpack") && !id.equals("create_jetpack:jetpack")) {
            return Snapshot.unavailable("no supported Create jetpack equipped in the chest slot");
        }
        try {
            Class<?> storage = Class.forName(FLIGHT + "logic.ISettingsStorage");
            if (!storage.isInstance(player)) return Snapshot.unavailable("FlightLib settings attachment unavailable");
            Map<?, ?> modes = (Map<?, ?>) storage.getMethod("flightlib$get").invoke(player);
            Object active = modes.get(key("TOGGLE_ACTIVE")), hover = modes.get(key("TOGGLE_HOVER"));
            if (!(active instanceof Boolean) || !(hover instanceof Boolean)) {
                return Snapshot.unavailable("FlightLib active/hover state has not been observed");
            }
            Object configs = singleton(CONFIG);
            Method synced = java.util.Arrays.stream(configs.getClass().getMethods())
                    .filter(m -> m.getName().startsWith("getSYNCED_SERVER$") && m.getParameterCount() == 0)
                    .findFirst().orElseThrow();
            Object config = synced.invoke(configs);
            if (config == null) return Snapshot.unavailable("Create jetpack server configuration has not synchronized");
            Object api = call(Class.forName(FLIGHT + "api.IFlightApi").getField("Companion").get(null), "getINSTANCE");
            Object nativeContext = call(api, "findJetpack", player);
            if (nativeContext == null || call(nativeContext, "getJetpack") != chest.getItem()) {
                return Snapshot.unavailable("FlightLib selected a different or invalid flight source");
            }
            Object jetpack = call(nativeContext, "getJetpack");
            @SuppressWarnings("unchecked") List<ItemStack> tanks = (List<ItemStack>) call(Class.forName(TANK), "getAllWithAir", player);
            int air = tanks.isEmpty() ? 0 : ((Number) call(Class.forName(TANK), "getAir", tanks.getFirst())).intValue();
            int base = ((Number) call(Class.forName(TANK), "maxAirWithoutEnchants")).intValue();
            int seconds = number(config, "getSecondsPerTankHover").intValue();
            return new Snapshot(true, "observed native client state", id, (Boolean) active, (Boolean) hover,
                    air, usableTicks(air, base, seconds), number(jetpack, "hoverHorizontalSpeed", nativeContext).doubleValue(),
                    number(jetpack, "hoverVerticalSpeed", nativeContext).doubleValue(),
                    number(jetpack, "acceleration", nativeContext).doubleValue(),
                    number(jetpack, "hoverSpeed", nativeContext).doubleValue(), player.getAttributeValue(Attributes.GRAVITY));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return Snapshot.unavailable("native jetpack API unavailable: " + failure.getClass().getSimpleName());
        }
    }

    /** Native isUsable uses float division; consumption uses floor integer division, minimum one.
     * Only the priority tank is credited: a nearly empty first tank can prevent using later tanks.
     * Reserve the last usable charge and the next unknown 20-tick billing boundary.
     */
    static int usableTicks(int air, int base, int seconds) {
        if (air < 0 || base <= 0 || seconds <= 0) return 0;
        int threshold = (int) Math.ceil((float) base / seconds);
        int cost = Math.max(base / seconds, 1);
        long ticks = Math.max(0L, ((long) air - threshold) / cost - 1) * 20;
        return (int) Math.min(Integer.MAX_VALUE, ticks);
    }

    public static NativeActionReceipt setMode(LocalPlayerContext ctx, boolean hover, boolean enabled) {
        String mode = hover ? "TOGGLE_HOVER" : "TOGGLE_ACTIVE";
        return ctx.actions().submitControlProtocol(ctx, "Create jetpack " + mode + "=" + enabled,
                () -> sendMode(mode, enabled), live -> {
                    Snapshot state = inspect(live);
                    if (!state.known()) return NativeConfirmation.Verdict.DIVERGED;
                    return (hover ? state.hover() : state.active()) == enabled
                            ? NativeConfirmation.Verdict.APPLIED : NativeConfirmation.Verdict.PENDING;
                }, 30);
    }

    private static void sendMode(String mode, boolean enabled) {
        try {
            Object key = key(mode);
            Class<?> eventType = Class.forName(FLIGHT + "logic.network.KeyPressedEvent");
            Object event = eventType.getConstructor(key.getClass(), boolean.class, boolean.class).newInstance(key, enabled, false);
            Object sender = singleton(FLIGHT + "logic.ControlSender");
            Method send = sender.getClass().getDeclaredMethod("send", eventType);
            send.setAccessible(true);
            send.invoke(sender, event);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("native FlightLib control submission failed", failure);
        }
    }

    private static Object key(String name) throws ReflectiveOperationException {
        return Class.forName(FLIGHT + "api.FlightKey").getField(name).get(null);
    }
    private static Object singleton(String type) throws ReflectiveOperationException {
        return Class.forName(type).getField("INSTANCE").get(null);
    }
    private static Number number(Object object, String name, Object... args) throws ReflectiveOperationException {
        return (Number) call(object, name, args);
    }
    private static Object call(Object object, String name, Object... args) throws ReflectiveOperationException {
        Class<?> type = object instanceof Class<?> clazz ? clazz : object.getClass();
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            boolean matches = true;
            for (int i = 0; i < args.length; i++) matches &= method.getParameterTypes()[i].isInstance(args[i]);
            if (matches) return method.invoke(object instanceof Class<?> ? null : object, args);
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }
}
