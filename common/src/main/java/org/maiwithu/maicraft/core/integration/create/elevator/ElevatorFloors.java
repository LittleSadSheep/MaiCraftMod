package org.maiwithu.maicraft.core.integration.create.elevator;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.player.LocalPlayer;

/**
 * 把当前观察到的楼层整理给用户选择，支持最高层、最低层、上一层、下一层和唯一名称；不把轿厢当前高度冒充玩家所在楼层。
 */
public final class ElevatorFloors {
    public record Floor(String id,int contactY,String shortName,String longName,boolean served) {}
    public record Elevator(UUID id,double distance,double approachDistance,Integer currentContactY,List<Floor> floors,boolean readyForDecision) {
        public Elevator { floors=List.copyOf(floors); }
        public Elevator(UUID id,double distance,double approachDistance,Integer currentContactY,List<Floor> floors) {
            this(id,distance,approachDistance,currentContactY,floors,approachDistance<=4);
        }
    }
    private ElevatorFloors() {}
    public static List<Elevator> observe(LocalPlayer player) {
        var bridge=ElevatorInspection.bridge();
        if(bridge==null || player==null) return List.of();
        return bridge.cabins(player).stream().map(c->new Elevator(c.entity().getUUID(),c.entity().distanceTo(player),
                Math.hypot(player.getX()-c.column().x()-.5,player.getZ()-c.column().z()-.5),
                sourceFloor(c,player),c.floors().stream()
                    .map(f->new Floor("floor:"+f.contactY(),f.contactY(),f.shortName(),f.longName(),c.serves(f.contactY())))
                    .sorted(Comparator.comparingInt(Floor::contactY)).toList(),player.onGround()
                        && (ElevatorInspection.supports(c,player) || bridge.recentSupport(c,player)
                            ? c.aligned(c.targetY()) : Math.hypot(player.getX()-c.column().x()-.5,player.getZ()-c.column().z()-.5)<=4)))
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
        return describe(elevator, null);
    }

    /**
     * 描述一个轿厢的楼层清单。
     *
     * <p>{@code referenceY} 给定时，每层附带**相对参考点**的高度差（{@code relative_y}）与
     * 上下关系（{@code above_reference}）——楼层标签（B / LG / G / R 之类）的含义由世界决定，
     * 这里不猜、也不翻译，但"哪层在参考点之上"是可算的事实：不给出这个事实，调用方只能靠
     * 标签猜高低，"从低层坐到高层"这类要求就可能被理解成坐到地下室去。
     *
     * <p>相对值不泄露绝对坐标（坐标仍留在本系统内），与 {@code location_boundary} 的口径一致。
     */
    public static Map<String,Object> describe(Elevator elevator, Double referenceY) {
        var rows=new java.util.ArrayList<>(elevator.floors().stream().limit(15).toList());
        if(elevator.floors().size()>15) rows.add(elevator.floors().getLast());
        var result=new java.util.LinkedHashMap<String,Object>();
        result.put("elevator_id",elevator.id().toString());
        result.put("distance",Math.round(elevator.distance()*10)/10D);
        result.put("approach_distance",Math.round(elevator.approachDistance()*10)/10D);
        result.put("floor_list_state",elevator.floors().isEmpty() ? "needs_sync" : "synchronized");
        result.put("floor_count",elevator.floors().size());
        result.put("floors_truncated",elevator.floors().size()>16);
        result.put("ready_for_floor_decision",elevator.readyForDecision());
        if(referenceY!=null) result.put("relative_to","player_feet");
        result.put("floors",rows.stream().map(f->floorRow(elevator,f,referenceY)).toList());
        return result;
    }

    private static Map<String,Object> floorRow(Elevator elevator,Floor floor,Double referenceY) {
        var row=new java.util.LinkedHashMap<String,Object>();
        row.put("id",floor.id());
        row.put("short_name",floor.shortName());
        row.put("long_name",floor.longName());
        row.put("served",floor.served());
        row.put("at_player_height",elevator.currentContactY()!=null && floor.contactY()==elevator.currentContactY());
        if(referenceY!=null) {
            row.put("relative_y",Math.round((floor.contactY()-referenceY)*10)/10D);
            row.put("above_reference",floor.contactY()>referenceY);
        }
        return row;
    }

    public static Map<String,Object> overview(LocalPlayer player) {
        try {
            var elevators=observe(player);
            Double referenceY=player==null ? null : player.getY();
            return Map.of("integration_available",ElevatorInspection.bridge()!=null,
                    "elevators",elevators.stream().limit(4).map(e->describe(e,referenceY)).toList(),"omitted",Math.max(0,elevators.size()-4));
        } catch(RuntimeException unavailable) { return Map.of("integration_available",false,"reason",String.valueOf(unavailable.getMessage())); }
    }
}
