package org.maiwithu.maicraft.core.integration.jetpack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.pathing.transport.TransportTargets.Destination;

/**
 * 把同高度、水平相邻的可站位置分成平台组，每组选一个靠近中心的真实落点作代表，其他落点仍保留。
 */
public record JetpackPlatform(Destination anchor, List<Vec3> landings) {
    public JetpackPlatform { landings = List.copyOf(landings); }

    public static List<JetpackPlatform> collect(List<Destination> destinations) {
        List<Destination> remaining = new ArrayList<>(destinations);
        List<JetpackPlatform> result = new ArrayList<>();
        while (!remaining.isEmpty()) {
            List<Destination> group = new ArrayList<>(); group.add(remaining.removeFirst());
            for (int i = 0; i < group.size(); i++) {
                Destination current = group.get(i);
                for (int j = remaining.size() - 1; j >= 0; j--) {
                    Destination next = remaining.get(j);
                    if (Math.abs(next.landingPoint().y - current.landingPoint().y) < 0.01
                            && Math.abs(next.feet().getX() - current.feet().getX())
                            + Math.abs(next.feet().getZ() - current.feet().getZ()) == 1) group.add(remaining.remove(j));
                }
            }
            Vec3 center = group.stream().map(Destination::landingPoint).reduce(Vec3.ZERO, Vec3::add).scale(1D / group.size());
            Destination anchor = group.stream().min(Comparator.comparingDouble(d -> d.landingPoint().distanceToSqr(center))).orElseThrow();
            result.add(new JetpackPlatform(anchor, group.stream().map(Destination::landingPoint).toList()));
        }
        return List.copyOf(result);
    }
}
