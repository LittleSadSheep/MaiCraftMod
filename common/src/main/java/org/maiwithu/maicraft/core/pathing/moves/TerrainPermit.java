package org.maiwithu.maicraft.core.pathing.moves;

/**
 * 本次导航能采用哪些改动：保持地形、只用临时水、允许落地辅助，或允许挖掘搭路。
 * 这是逐级扩大的许可：允许挖掘搭路的 TERRAFORM 同时允许水桶和落地辅助，不是三个互相独立的开关。
 */
public enum TerrainPermit {
    /** 只走不改:任何挖掘与放置在成本模型里都是 INF。 */
    PRESERVE,
    /** 仅允许落地水与回收本次放出的水；不允许挖掘、放块或搭路。 */
    WATER_ONLY,
    /** 仅允许本次落地辅助及有归因的回收，不授权普通挖掘、架桥或搭路。 */
    LANDING_ONLY,
    /** 可改地形:挖穿、垫路、搭柱、架桥都可入路,受总开关与硬禁挖标签约束。 */
    TERRAFORM;

    public boolean mayAlter() {
        return this == TERRAFORM;
    }

    public boolean mayUseWaterBucket() {
        return this == WATER_ONLY || this == LANDING_ONLY || this == TERRAFORM;
    }

    public boolean mayUseLandingAssists() { return this == LANDING_ONLY || this == TERRAFORM; }
}
