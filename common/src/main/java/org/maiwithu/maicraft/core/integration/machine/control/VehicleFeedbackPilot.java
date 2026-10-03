package org.maiwithu.maicraft.core.integration.machine.control;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.phys.Vec3;

/** 学习较小的原生控制响应；不建立力学模型，也不假设键盘方向映射。 */
public final class VehicleFeedbackPilot {
    public enum Phase { BASELINE, PROBE, SETTLE, DRIVE, BRAKE, DONE, FAILED }
    public record Sample(Vec3 position,double yaw) {}
    public record Response(String input,double value,Vec3 localVelocity,double yawRate,boolean propulsion) {}
    private record Probe(VehicleControlPlan.Input input,double value) {}
    private final VehicleControlPlan plan;
    private final Vec3 destination;
    private final List<Probe> probes=new ArrayList<>();
    private final List<Response> responses=new ArrayList<>();
    private Phase phase=Phase.BASELINE;
    private Sample previous,start,origin;
    private long previousTick=-1,phaseTick=-1;
    private int probeIndex,stable;
    private boolean cancelled;
    private String failure="";
    private Response propulsion;
    private double speed;
    private double angularSpeed;
    private double bestDistance=Double.POSITIVE_INFINITY;
    private long lastProgress=-1;
    public VehicleFeedbackPilot(VehicleControlPlan plan,Vec3 destination) {
        if(!plan.usable()) throw new IllegalArgumentException("control plan unresolved: "+plan.limitations());
        this.plan=plan; this.destination=destination;
        plan.inputs().stream().sorted(Comparator.comparing(i->!i.propulsion())).forEach(i->i.probes().forEach(v->probes.add(new Probe(i,v))));
    }
    public Map<String,Double> command() {
        var result=new LinkedHashMap<>(plan.neutral());
        if(phase==Phase.PROBE) {
            Probe probe=probes.get(probeIndex);
            if(!probe.input().propulsion() && propulsion!=null) result.put(propulsion.input(),propulsion.value());
            result.put(probe.input().id(),probe.value());
        } else if(phase==Phase.DRIVE && previous!=null && propulsion!=null) {
            Vec3 target=destination.subtract(previous.position());
            Response drive=responses.stream().filter(r->r.propulsion() && r.localVelocity().horizontalDistance()>.003)
                    .max(Comparator.comparingDouble(r->world(r.localVelocity(),previous.yaw()).normalize().dot(target.normalize())))
                    .orElse(propulsion);
            result.put(drive.input(),drive.value());
            double desiredHeading=Math.atan2(target.x,target.z);
            double driveHeading=previous.yaw()+Math.atan2(drive.localVelocity().x,drive.localVelocity().z);
            double error=wrap(desiredHeading-driveHeading);
            if(Math.abs(error)>.10) responses.stream().filter(r->!r.propulsion() && r.yawRate()*error>0 && usefulTurn(r))
                    .max(Comparator.comparingDouble(r->Math.abs(r.yawRate())))
                    .ifPresent(r->result.put(r.input(),r.value()));
        }
        return result;
    }
    public void observe(long tick,Sample sample,boolean inputsApplied) {
        if(previousTick==tick || terminal()) return;
        if(origin==null) origin=sample;
        speed=previous==null ? 0:sample.position().distanceTo(previous.position())/Math.max(1,tick-previousTick);
        angularSpeed=previous==null ? 0:Math.abs(wrap(sample.yaw()-previous.yaw()))/Math.max(1,tick-previousTick);
        previous=sample; previousTick=tick;
        if(speed>.25) { stop("vehicle exceeded the measured driving speed limit"); }
        if(phase!=Phase.DRIVE && phase!=Phase.BRAKE && sample.position().distanceTo(origin.position())>12)
            stop("calibration displacement limit reached");
        if(!inputsApplied) { phaseTick=-1; stable=0; return; }
        if(phaseTick<0) { phaseTick=tick; start=sample; }
        stable=speed<.015 && angularSpeed<.003 ? stable+1:0;
        switch(phase) {
            case BASELINE -> {
                if(stable>=8) transition(Phase.PROBE);
                else if(tick-phaseTick>80) stop("neutral controls did not stop the structure before calibration");
            }
            case PROBE -> {
                Probe probe=probes.get(probeIndex);
                // 比较器、无线链路和轮座追随角都有原生延迟；转向观察窗口覆盖这些延迟后再判断响应。
                if(tick-phaseTick>=(probe.input().kind()==ControlCircuit.Kind.STEERING_WHEEL?24:12)) {
                    double elapsed=tick-phaseTick;
                    Vec3 velocity=local(sample.position().subtract(start.position()).scale(1/elapsed),start.yaw());
                    double turn=wrap(sample.yaw()-start.yaw())/elapsed;
                    if(!probe.input().propulsion() && propulsion!=null) turn-=propulsion.yawRate();
                    var response=new Response(probe.input().id(),probe.value(),velocity,turn,probe.input().propulsion());
                    responses.add(response);
                    if(response.propulsion() && velocity.horizontalDistance()>.003 && (propulsion==null
                            || velocity.horizontalDistance()>propulsion.localVelocity().horizontalDistance())) propulsion=response;
                    transition(Phase.SETTLE);
                }
            }
            case SETTLE -> {
                if(stable>=8) {
                    // 每次恢复中性并停稳后再选下一档；已经测到同方向推进时保留低档，避免为校准继续猛踩油门。
                    probeIndex++;
                    while(probeIndex<probes.size()&&responseKnown(probes.get(probeIndex)))probeIndex++;
                    if(probeIndex<probes.size()) transition(Phase.PROBE);
                    else if(propulsion==null) stop("no propulsion response was observed for the connected controls");
                    else transition(Phase.DRIVE);
                } else if(tick-phaseTick>80) stop("neutral control response did not settle after calibration");
            }
            case DRIVE -> {
                double distance=sample.position().distanceTo(destination);
                if(distance<bestDistance-.1 || lastProgress<0) { bestDistance=distance; lastProgress=tick; }
                if(near(sample.position())) transition(Phase.BRAKE);
                else if(tick-lastProgress>160) stop("observed controls no longer make progress toward the destination");
            }
            case BRAKE -> {
                if(stable>=8) {
                    if(!failure.isEmpty() || cancelled) transition(Phase.FAILED);
                    else if(near(sample.position())) transition(Phase.DONE);
                    else transition(Phase.DRIVE);
                } else if(tick-phaseTick>100) { failure="stop was requested but structure motion did not settle"; transition(Phase.FAILED); }
            }
            default -> { }
        }
    }
    public void stop(String reason) { if(failure.isEmpty()) failure=reason; if(!terminal() && phase!=Phase.BRAKE) transition(Phase.BRAKE); }
    private boolean responseKnown(Probe probe) {
        boolean drive=probe.input().propulsion();
        if(!drive&&probe.input().kind()!=ControlCircuit.Kind.STEERING_WHEEL)return false;
        double delta=probe.value()-probe.input().neutral();
        return responses.stream().anyMatch(r->r.propulsion()==drive&&r.input().equals(probe.input().id())
                &&(drive?r.localVelocity().horizontalDistance()>.003:Math.abs(r.yawRate())>.0005)
                &&Math.signum(r.value()-probe.input().neutral())==Math.signum(delta)
                &&Math.abs(r.value()-probe.input().neutral())<Math.abs(delta));
    }
    private static boolean usefulTurn(Response response) {
        double turn=Math.abs(response.yawRate()),speed=response.localVelocity().horizontalDistance();
        // 慢速收车时每刻转角虽小，单位行驶距离仍有明确转向；保留噪声下限，再按曲率识别这种有效响应。
        return turn>.0005 || turn>.00005&&speed>.003&&turn/speed>.01;
    }
    public void cancel() { cancelled=true; stop("driving cancelled; neutral controls requested"); }
    private boolean near(Vec3 point) { return point.subtract(destination).horizontalDistance()<=3 && Math.abs(point.y-destination.y)<=3; }
    private void transition(Phase next) { phase=next; phaseTick=-1; stable=0; }
    private static Vec3 world(Vec3 value,double yaw) {
        double c=Math.cos(yaw),s=Math.sin(yaw); return new Vec3(value.x*c+value.z*s,value.y,-value.x*s+value.z*c);
    }
    private static Vec3 local(Vec3 value,double yaw) { return world(value,-yaw); }
    static double wrap(double value) { return Math.atan2(Math.sin(value),Math.cos(value)); }
    public Phase phase() { return phase; }
    public boolean terminal() { return phase==Phase.DONE || phase==Phase.FAILED; }
    public boolean succeeded() { return phase==Phase.DONE; }
    public String failure() { return failure; }
    public double speed() { return speed; }
    public Map<String,Object> diagnostics() { return Map.of("phase",phase.name().toLowerCase(),"observed_speed_blocks_per_tick",speed,
            "observed_angular_speed_radians_per_tick",angularSpeed,
            "responses",List.copyOf(responses),"input_confirmation","native dispatch/analog update; actual movement independently observed",
            "force_analysis","not implemented by this controller","detail",failure); }
}
