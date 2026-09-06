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
                    && Double.isFinite(hoverDescent) && hoverDescent < 0 && hoverDescent >= -0.2
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

    /** Native client evaluation, including the actual active-context gate. This is never a server ACK. */
    public static Map<String, Object> activeEvidence(net.minecraft.client.player.LocalPlayer player) {
        Map<String, Object> evidence = new java.util.LinkedHashMap<>();
        evidence.put("known", false);
        evidence.put("scope", "client_only_no_server_ack");
        if (player == null) {
            evidence.put("reason", "local player unavailable");
            return Map.copyOf(evidence);
        }
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft == null || !minecraft.isSameThread()) throw new IllegalStateException("client-thread jetpack observation required");
        try {
            evidence.put("abilities_flying", player.getAbilities().flying);
            evidence.put("passenger", player.isPassenger()); evidence.put("on_ground", player.onGround());
            evidence.put("fall_flying", player.isFallFlying()); evidence.put("visually_swimming", player.isVisuallySwimming());
            evidence.put("sprinting", player.isSprinting()); evidence.put("affected_by_fluids", player.isAffectedByFluids());
            evidence.put("input_present", player.input != null);
            if (player.input != null) evidence.put("input_jumping", player.input.jumping);
            evidence.put("entity_shift_down", player.isShiftKeyDown());
            evidence.put("velocity_y", player.getDeltaMovement().y); evidence.put("fall_distance", player.fallDistance);
            Object api = call(Class.forName(FLIGHT + "api.IFlightApi").getField("Companion").get(null), "getINSTANCE");
            evidence.put("native_up", (Boolean) call(key("UP"), "isPressed", player));
            Object active = call(api, "findActiveJetpack", player);
            Object context = active != null ? active : call(api, "findJetpack", player);
            evidence.put("active_context_present", active != null);
            evidence.put("active_source_matches_chest", false);
            if (context != null) {
                Object jetpack = call(context, "getJetpack"), source = call(context, "getSource");
                evidence.put("native_pose", ((Enum<?>) call(context, "getPose")).name());
                evidence.put("native_usable", (Boolean) call(jetpack, "isUsable", context));
                evidence.put("native_source_disabled", (Boolean) call(source, "isDisabled", context));
                evidence.put("native_hover", (Boolean) call(jetpack, "isHovering", context));
                Class<?> equipment = Class.forName(FLIGHT + "api.sources.EquipmentSource");
                ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
                String id = BuiltInRegistries.ITEM.getKey(chest.getItem()).toString();
                boolean supported = id.equals("create_jetpack:jetpack") || id.equals("create_jetpack:netherite_jetpack");
                evidence.put("active_source_matches_chest", active != null && supported && jetpack == chest.getItem()
                        && equipment.isInstance(source) && call(source, "getSlot") == EquipmentSlot.CHEST);
            } else evidence.put("reason", "no native jetpack context");
            evidence.put("known", true);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            evidence.put("known", false);
            evidence.put("reason", "native active-context observation failed: " + failure.getClass().getSimpleName());
        }
        return Map.copyOf(evidence);
    }

    /** A compatible native upright context, not a claim that the server cleared fall distance. */
    public static boolean uprightActive(Map<String, Object> evidence) {
        return evidence != null && "client_only_no_server_ack".equals(evidence.get("scope"))
                && Boolean.TRUE.equals(evidence.get("known")) && Boolean.TRUE.equals(evidence.get("active_context_present"))
                && Boolean.TRUE.equals(evidence.get("active_source_matches_chest")) && "UPRIGHT".equals(evidence.get("native_pose"))
                && Boolean.TRUE.equals(evidence.get("native_usable")) && Boolean.FALSE.equals(evidence.get("native_source_disabled"))
                && Boolean.FALSE.equals(evidence.get("abilities_flying")) && Boolean.FALSE.equals(evidence.get("passenger"));
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
