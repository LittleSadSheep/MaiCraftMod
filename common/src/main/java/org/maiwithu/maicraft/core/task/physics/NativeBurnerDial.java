package org.maiwithu.maicraft.core.task.physics;

import java.util.Collection;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** 沿原生热气燃烧器面板的容量刻度换算，不访问私有字段或直接写供气量。 */
final class NativeBurnerDial {
    static final String BURNER="dev.eriksonn.aeronautics.content.blocks.hot_air.hot_air_burner.HotAirBurnerBlockEntity";
    private static final String BEHAVIOUR="dev.eriksonn.aeronautics.content.blocks.hot_air.hot_air_burner.HotAirBurnerValueBehaviour";
    private NativeBurnerDial() {}
    static Object setting(Object burner) {
        return ((Collection<?>) NativeApi.call(burner,null,"getAllBehaviours")).stream()
                .filter(value -> NativeApi.is(value,BEHAVIOUR)).findFirst()
                .orElseThrow(() -> new IllegalStateException("原生燃烧器容积面板尚未同步"));
    }
    static int maximum() {
        Object server=NativeApi.call(null,"dev.eriksonn.aeronautics.config.AeroConfig","server");
        Object blocks=NativeApi.field(server,null,"blocks");
        return ((Number) NativeApi.call(NativeApi.field(blocks,null,"hotAirBurnerMaxHotAir"),null,"get")).intValue();
    }
    static int interval(int maximum) {
        if(maximum<5)throw new IllegalArgumentException("原生燃烧器最大容量低于面板最小值");
        return (int)Math.max(1,((long)maximum-5+250)/500*5);
    }
    static int column(int requested,int maximum) {
        if(requested<5||requested>maximum)throw new IllegalArgumentException("请求超出原生燃烧器容量范围: 5.."+maximum);
        return requested/interval(maximum);
    }
    static int applied(int requested,int maximum) {
        // UI 最小档会钳到五立方；其他数量按原生整数列向下取档，实际值必须在回执中明示。
        return Math.clamp(column(requested,maximum)*interval(maximum),5,maximum);
    }
}
