package org.maiwithu.maicraft.core.scan;

/**
 * 从中心区块向外一圈圈枚举方形边界，不在这里访问世界。
 * 调用方负责给有效的圈数和圈内序号，当前方法没有另做范围校验。
 */
public final class RingSpiral {

    private RingSpiral() {}

    /** {@code ring} 周长包含的格子数；中心环为 1。 */
    public static int perimeter(int ring) {
        return ring == 0 ? 1 : 8 * ring;
    }

    /**
     * 返回 {@code ring} 周长上第 {@code idx} 个格子相对于螺旋中心的 (dx, dz) 偏移。
     * {@code idx} 必须位于 {@code [0, perimeter(ring))} 范围内。
     */
    // 中心圈只有原点；其他圈按四条边依次给出偏移，每条边两倍圈数个位置，避免角点重复。
    public static int[] offset(int ring, int idx) {
        if (ring == 0) return new int[]{0, 0};
        int side = idx / (2 * ring);
        int t = idx % (2 * ring);
        return switch (side) {
            case 0 -> new int[]{-ring + t, -ring};
            case 1 -> new int[]{ring, -ring + t};
            case 2 -> new int[]{ring - t, ring};
            default -> new int[]{-ring, ring - t};
        };
    }
}
