package org.maiwithu.maicraft.core.pathing.transport;

import java.util.Locale;

/** 用户选择移动方式；auto 可在实际可用的方案中选择，指定方式不会自动授权挖地形或消耗落地材料。 */
public enum TransportMode {
    AUTO, GROUND, JETPACK, ELEVATOR;

    public static TransportMode parse(String value) {
        // 未填写默认 auto，大小写和两端空白可兼容；其他名称明确拒绝，不悄悄回退到走路。
        if (value == null) return AUTO;
        try { return valueOf(value.strip().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("transport_mode must be auto, ground, jetpack or elevator");
        }
    }
}
