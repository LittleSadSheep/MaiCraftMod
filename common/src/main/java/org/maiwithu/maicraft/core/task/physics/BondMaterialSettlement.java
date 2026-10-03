package org.maiwithu.maicraft.core.task.physics;

import java.util.Map;

/** 胶实体与背包同步可能分刻到达；确认胶层后继续等待耗材稳定，不拿旧耐久冒充零消耗。 */
final class BondMaterialSettlement {
    private final Map<String,Object> before;
    private final long started;
    private Map<String,Object> after=Map.of();
    private long lastTick=Long.MIN_VALUE;
    private int stable;
    BondMaterialSettlement(Map<String,Object> before,long started) {this.before=Map.copyOf(before);this.started=started;}
    boolean observe(long tick,Map<String,Object> current) {
        if(tick!=lastTick) {
            stable=current.equals(after)?stable+1:1;after=Map.copyOf(current);lastTick=tick;
        }
        return stable>=2&&(changed()||Boolean.TRUE.equals(before.get("creative"))&&Boolean.TRUE.equals(after.get("creative")))
                ||tick-started>=40;
    }
    private boolean changed() {
        return !before.get("count").equals(after.get("count"))||!before.get("remaining_durability").equals(after.get("remaining_durability"));
    }
    Map<String,Object> evidence() {
        // 无变化且观察窗口耗尽只表示未确认扣减，不能宣称生存模式的胶水免费。
        String state=changed()?"material_change_observed":Boolean.TRUE.equals(before.get("creative"))&&Boolean.TRUE.equals(after.get("creative"))
                ?"creative_no_charge_observed":"consumption_not_observed";
        return Map.of("state",state,"stable_samples",stable,"observed_ticks",Math.max(0,lastTick-started));
    }
}
