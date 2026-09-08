package org.maiwithu.maicraft.core.integration.create.elevator;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.player.LocalPlayer;

/** Synchronized floor identities; names and contact heights never come from guessed landmarks. */
public final class ElevatorFloors {
    public record Floor(String id,int contactY,String shortName,String longName,boolean served) {}
    public record Elevator(UUID id,double distance,double approachDistance,Integer currentContactY,List<Floor> floors) {
        public Elevator { floors=List.copyOf(floors); }
    }
    private ElevatorFloors() {}
    public static List<Elevator> observe(LocalPlayer player) {
        var bridge=ElevatorInspection.bridge();
        if(bridge==null || player==null) return List.of();
        return bridge.cabins(player).stream().map(c->new Elevator(c.entity().getUUID(),c.entity().distanceTo(player),
                Math.hypot(player.getX()-c.column().x()-.5,player.getZ()-c.column().z()-.5),
                sourceFloor(c,player),c.floors().stream()
                    .map(f->new Floor("floor:"+f.contactY(),f.contactY(),f.shortName(),f.longName(),c.serves(f.contactY())))
                    .sorted(Comparator.comparingInt(Floor::contactY)).toList()))
                .sorted(Comparator.comparingDouble(Elevator::approachDistance).thenComparingDouble(Elevator::distance)).toList();
    }
    private static Integer sourceFloor(CreateElevatorBridge.Cabin cabin,LocalPlayer player) {
        var geometry=new ElevatorGeometry(cabin.blocks(),cabin.view(),player.getBbWidth(),player.getBbHeight());
        var matches=cabin.floors().stream().filter(f->cabin.serves(f.contactY()) && geometry.stances.stream()
                .anyMatch(p->Math.abs(cabin.originAt(f.contactY()).y+p.y-player.getY())<.2)).toList();
        return matches.size()==1 ? matches.getFirst().contactY() : null;
    }
    public static Floor select(Elevator elevator,String requested) {
        var served=elevator.floors().stream().filter(Floor::served).toList();
        return switch(requested) {
            case "top" -> served.stream().max(Comparator.comparingInt(Floor::contactY)).orElse(null);
            case "bottom" -> served.stream().min(Comparator.comparingInt(Floor::contactY)).orElse(null);
            case "next_up" -> served.stream().filter(f->elevator.currentContactY()!=null && f.contactY()>elevator.currentContactY())
                    .min(Comparator.comparingInt(Floor::contactY)).orElse(null);
            case "next_down" -> served.stream().filter(f->elevator.currentContactY()!=null && f.contactY()<elevator.currentContactY())
                    .max(Comparator.comparingInt(Floor::contactY)).orElse(null);
            default -> {
                var matches=served.stream().filter(f->f.id().equals(requested) || f.shortName().equals(requested) || f.longName().equals(requested)).toList();
                yield matches.size()==1 ? matches.getFirst() : null;
            }
        };
    }
    public static Map<String,Object> describe(Elevator elevator) {
        var rows=new java.util.ArrayList<>(elevator.floors().stream().limit(15).toList());
        if(elevator.floors().size()>15) rows.add(elevator.floors().getLast());
        return Map.of("elevator_id",elevator.id().toString(),"distance",Math.round(elevator.distance()*10)/10D,
                "approach_distance",Math.round(elevator.approachDistance()*10)/10D,
                "floor_list_state",elevator.floors().isEmpty() ? "needs_sync" : "synchronized",
                "floor_count",elevator.floors().size(),"floors_truncated",elevator.floors().size()>16,
                "floors",rows.stream().map(f->Map.of("id",f.id(),"short_name",f.shortName(),"long_name",f.longName(),
                        "served",f.served(),"at_player_height",elevator.currentContactY()!=null && f.contactY()==elevator.currentContactY())).toList());
    }
    public static Map<String,Object> overview(LocalPlayer player) {
        try {
            var elevators=observe(player);
            return Map.of("integration_available",ElevatorInspection.bridge()!=null,
                    "elevators",elevators.stream().limit(4).map(ElevatorFloors::describe).toList(),"omitted",Math.max(0,elevators.size()-4));
        } catch(RuntimeException unavailable) { return Map.of("integration_available",false,"reason",String.valueOf(unavailable.getMessage())); }
    }
}
