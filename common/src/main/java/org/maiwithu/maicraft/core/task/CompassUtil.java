package org.maiwithu.maicraft.core.task;

/**
 * 把目标相对玩家的位置写成东、西、东北等方向。
 * 例如东向距离至少是北向的两倍时，只写东；两个方向接近时才写东北。
 */
public final class CompassUtil {

    private CompassUtil() {}

    public static String compass(int dx, int dz) {
        if (dx == 0 && dz == 0) return "here";
        String ns = dz < 0 ? "north" : (dz > 0 ? "south" : "");
        String ew = dx < 0 ? "west" : (dx > 0 ? "east" : "");
        if (ns.isEmpty()) return ew;
        if (ew.isEmpty()) return ns;
        if (Math.abs(dz) >= 2 * Math.abs(dx)) return ns;
        if (Math.abs(dx) >= 2 * Math.abs(dz)) return ew;
        return ns + "-" + ew;
    }
}
