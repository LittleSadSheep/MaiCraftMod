package org.maiwithu.maicraft.core.integration.physics.flight;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.EnumMap;

/** 连续飞控意图按游戏刻调制成已有无线键；同一轴只选一个方向，键的变化由原生执行器发送。 */
public final class FlightKeyMixer {
    public enum Role { POWER, BRAKE, PITCH_UP, PITCH_DOWN, BANK_LEFT, BANK_RIGHT, YAW_LEFT, YAW_RIGHT, LIFT }
    private final Map<Role,Integer> keys;
    private final Map<Role,Double> remaining=new EnumMap<>(Role.class);
    private final Map<Role,Long> quantum=new EnumMap<>(Role.class);
    private final Map<Role,Boolean> active=new EnumMap<>(Role.class);
    private long previousTick=Long.MIN_VALUE;
    private Set<Integer> previous=Set.of();
    public FlightKeyMixer(Map<Role,Integer> keys) {
        this.keys=Map.copyOf(keys);
        if(!keys.containsKey(Role.POWER))throw new IllegalArgumentException("flight power key is required");
    }
    public Set<Integer> tick(long tick,FlightCommand command) {
        if(tick<=previousTick)return previous;
        previousTick=tick;var desired=new LinkedHashSet<Integer>();
        add(desired,Role.POWER,command.power());add(desired,Role.LIFT,command.lift());
        if(command.brake()&&keys.containsKey(Role.BRAKE))desired.add(keys.get(Role.BRAKE));
        // 微小输出累计到整刻才按一次；零输出清除旧累计，避免停机或换向后迟来的旧脉冲。
        axis(desired,Role.PITCH_UP,Role.PITCH_DOWN,command.pitch());
        axis(desired,Role.BANK_RIGHT,Role.BANK_LEFT,command.bank());
        axis(desired,Role.YAW_RIGHT,Role.YAW_LEFT,command.yaw());
        return previous=Set.copyOf(desired);
    }
    private void axis(Set<Integer> output,Role positive,Role negative,double value) {
        add(output,positive,Math.max(0,value));add(output,negative,Math.max(0,-value));
    }
    private void add(Set<Integer> output,Role role,double demand) {
        if(!keys.containsKey(role))return;
        if(demand<=0){remaining.remove(role);quantum.remove(role);active.remove(role);return;}
        if(demand>=1){remaining.remove(role);output.add(keys.get(role));active.put(role,true);quantum.remove(role);return;}
        // 换向器和弹簧需要原生更新时间，部分输出按四刻成组保持；满量与松键仍立即响应。
        long group=previousTick/4;
        if(quantum.getOrDefault(role,Long.MIN_VALUE)==group){if(active.getOrDefault(role,false))output.add(keys.get(role));return;}
        quantum.put(role,group);
        double accumulated=remaining.getOrDefault(role,0.0)+demand;
        boolean down=accumulated>=1-1e-9;active.put(role,down);
        if(down){output.add(keys.get(role));accumulated-=1;}
        remaining.put(role,accumulated);
    }
    public Set<Integer> configuredKeys(){return Set.copyOf(keys.values());}
    public Map<Role,Integer> bindings(){return keys;}
}
