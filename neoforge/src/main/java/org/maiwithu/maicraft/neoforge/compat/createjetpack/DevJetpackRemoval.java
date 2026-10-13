// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.createjetpack;

import java.lang.reflect.Field;
import java.util.List;

import com.possible_triangle.flightlib.init.FlightApiImpl;
import net.neoforged.fml.loading.FMLLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 开发环境里 FlightLib 自己登记了一个调试用的"喷气背包"：手里拿盾、身上穿钻石胸甲就能飞，不耗气，悬停时原地不动。
 * 正式整合包里没有它。实机测试跑在开发环境，角色副手拿着盾，飞行开关又还开着，就被它带着飞：
 * 真喷气背包没气了、摘下来了还在半空悬着下不来，测出来的和玩家玩的不一样。只在开发环境摘掉它，正式环境什么都不做。
 */
final class DevJetpackRemoval {

    private static final Logger LOG = LoggerFactory.getLogger(DevJetpackRemoval.class);
    /** FlightLib 登记调试喷气背包的地方：它只在开发环境往认装备的名单里加这一条。 */
    private static final String DEV_SOURCES = "com.possible_triangle.flightlib.init.CommonSources";

    private DevJetpackRemoval() {}

    // 开发环境：从 FlightLib 认装备的名单里拿掉调试那一条；读不到名单就照旧，记一笔。
    static void removeInDevelopment() {
        if (FMLLoader.isProduction()) return;
        try {
            Field field = FlightApiImpl.class.getDeclaredField("CASTERS");
            field.setAccessible(true);
            List<?> casters = (List<?>) field.get(FlightApiImpl.INSTANCE);
            int before = casters.size();
            casters.removeIf(caster -> caster.getClass().getName().startsWith(DEV_SOURCES));
            LOG.info("[喷气背包] 开发环境：摘掉 FlightLib 的调试喷气背包（盾牌、钻石胸甲当喷气背包）{} 条",
                    before - casters.size());
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            LOG.warn("[喷气背包] 开发环境：没能摘掉 FlightLib 的调试喷气背包，拿盾时可能被它带着飞：{}",
                    unreadable.toString());
        }
    }
}
