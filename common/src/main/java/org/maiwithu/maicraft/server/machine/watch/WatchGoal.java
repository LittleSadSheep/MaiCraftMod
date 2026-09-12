// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.watch;

import com.google.gson.JsonObject;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;
import org.maiwithu.maicraft.server.machine.ServerAccess;

/** Immutable, finite observation intent. Positions are authorized separately on the actual server player. */
public record WatchGoal(String dimension, List<Process> processes, BlockPos sink, Direction sinkSide,
                        JsonObject identity, String resourceId, long minimumOutput, long idleTicks,
                        long maximumTicks, JsonObject specification) {
    public static final int MAX_PROCESSES = 8;
    public record Process(String id, BlockPos position, BlockPos outputPosition, String recipeId, int minimumEvents) {
        public Process { position = position.immutable(); outputPosition = outputPosition.immutable(); }
    }
    public WatchGoal {
        processes = List.copyOf(processes); sink = sink.immutable(); identity = identity.deepCopy(); specification = specification.deepCopy();
    }
    @Override public JsonObject identity() { return identity.deepCopy(); }
    @Override public JsonObject specification() { return specification.deepCopy(); }

    public static WatchGoal parse(JsonObject goal) {
        keys(goal,"dimension","processes","sink","target","idle_ticks","max_duration_ticks");
        String dimension = text(goal,"dimension",128);
        var raw = goal.getAsJsonArray("processes");
        if (raw == null || raw.isEmpty() || raw.size() > MAX_PROCESSES) throw invalid("One to eight native processes are required");
        var processes = new java.util.ArrayList<Process>();
        Set<String> ids = new LinkedHashSet<>(); Set<BlockPos> producers = new LinkedHashSet<>(), outputs = new LinkedHashSet<>();
        for (var value : raw) {
            JsonObject row = value.getAsJsonObject(); keys(row,"id","position","output_position","recipe_id","minimum_events");
            String id = text(row,"id",64); BlockPos position = ServerAccess.position(row.getAsJsonObject("position"));
            BlockPos output = ServerAccess.position(row.getAsJsonObject("output_position"));
            if (!ids.add(id) || !producers.add(position) || !outputs.add(output)) throw invalid("Process identities and native output positions must be distinct");
            processes.add(new Process(id,position,output,text(row,"recipe_id",256),ServerAccess.integer(row,"minimum_events",1,10_000)));
        }
        JsonObject target = goal.getAsJsonObject("target"); keys(target,"identity","resource_id","minimum_output");
        JsonObject identity = target.getAsJsonObject("identity");
        if (identity == null || !"items".equals(text(identity,"kind",32)) || !identity.has("components") || !identity.get("components").isJsonObject())
            throw invalid("An exact native item identity is required");
        text(identity,"id",256); String resource = text(target,"resource_id",1024);
        if (!ResourceIdentity.key(identity).equals(resource)) throw invalid("Resource identity and component key differ");
        JsonObject sink = goal.getAsJsonObject("sink"); keys(sink,"position","side");
        long duration = ServerAccess.integer(goal,"max_duration_ticks",20,72_000);
        long idle = ServerAccess.integer(goal,"idle_ticks",20,(int) duration);
        return new WatchGoal(dimension,processes,ServerAccess.position(sink.getAsJsonObject("position")),ServerAccess.side(sink),
                identity,resource,ServerAccess.integer(target,"minimum_output",1,1_000_000),idle,duration,goal);
    }
    public Set<BlockPos> positions() {
        Set<BlockPos> result = new LinkedHashSet<>();
        processes.forEach(process -> { result.add(process.position()); result.add(process.outputPosition()); }); result.add(sink);
        return Set.copyOf(result);
    }
    static void keys(JsonObject value, String... allowed) {
        if (value == null || !Set.of(allowed).containsAll(value.keySet())) throw invalid("Unexpected monitor fields");
    }
    static String text(JsonObject value, String name, int maximum) {
        if (value == null || !value.has(name) || !value.get(name).isJsonPrimitive() || !value.getAsJsonPrimitive(name).isString()) throw invalid("Missing " + name);
        String result = value.get(name).getAsString();
        if (result.isBlank() || result.length() > maximum) throw invalid("Invalid " + name);
        return result;
    }
    static IllegalArgumentException invalid(String reason) { return new IllegalArgumentException(reason); }
}
