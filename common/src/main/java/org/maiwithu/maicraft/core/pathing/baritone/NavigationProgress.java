package org.maiwithu.maicraft.core.pathing.baritone;

/**
 * 记录导航最近是否有实际进展：位置累计变化至少四分之一格，或确认完成一次原生操作。首次采样只记起点，不算进展。
 * 这里不要求一直更接近终点，绕路时也可以续期；是否走偏或卡住由其他导航逻辑判断。
 */
final class NavigationProgress {
    private static final double MIN_DISPLACEMENT_SQUARED = 0.25 * 0.25;
    private double x, y, z;
    private boolean sampled;
    private long started = Long.MIN_VALUE;
    private long confirmed = Long.MIN_VALUE;

    void observe(double nextX, double nextY, double nextZ, long tick) {
        if (started == Long.MIN_VALUE) started = tick;
        double dx = nextX - x, dy = nextY - y, dz = nextZ - z;
        if (!sampled || dx * dx + dy * dy + dz * dz >= MIN_DISPLACEMENT_SQUARED) {
            if (sampled) confirm(tick);
            x = nextX;
            y = nextY;
            z = nextZ;
            sampled = true;
        }
    }

    void confirm(long tick) { confirmed = tick; }

    boolean recent(long tick, int grace) {
        return confirmed != Long.MIN_VALUE && age(tick, confirmed) <= Math.max(0, grace);
    }

    int stalledTicks(long tick) {
        return age(tick, confirmed == Long.MIN_VALUE ? started : confirmed);
    }

    private static int age(long tick, long since) {
        if (since == Long.MIN_VALUE || tick < since) return Integer.MAX_VALUE;
        return (int) Math.min(Integer.MAX_VALUE, tick - since);
    }
}
