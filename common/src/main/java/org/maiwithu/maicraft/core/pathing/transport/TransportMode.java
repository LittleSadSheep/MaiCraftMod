package org.maiwithu.maicraft.core.pathing.transport;

import java.util.Locale;

/** User intent; automatic transport may use the equipment and elevator access actually observed. */
public enum TransportMode {
    AUTO, GROUND, JETPACK, ELEVATOR;

    public static TransportMode parse(String value) {
        if (value == null) return AUTO;
        try { return valueOf(value.strip().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("transport_mode must be auto, ground, jetpack or elevator");
        }
    }
}
