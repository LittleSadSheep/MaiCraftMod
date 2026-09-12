// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.intent.IntentRuntime;

/** Background status only: no task dispatch, movement, menu operation or automatic refill. */
public final class ClientMachineWatches {
    private static final Map<UUID, Job> jobs = new LinkedHashMap<>();
    private static LocalPlayer player;
    private static Object level;
    private static final class Job {
        final UUID id; final String label;
        JsonObject report; ClientRequestReceipt request; long nextPoll; String notified = ""; boolean cancel;
        Job(UUID id, String label, JsonObject report) { this.id = id; this.label = label; this.report = report.deepCopy(); }
    }
    private ClientMachineWatches() {}

    public static void track(LocalPlayer current, UUID id, String label, JsonObject report) {
        bind(current);
        if (!report.has("armed") || !report.get("armed").getAsBoolean() || !report.get("monitor_active").getAsBoolean())
            throw new IllegalArgumentException("machine_watch_not_armed");
        if (!jobs.containsKey(id) && jobs.size() >= 16) {
            var terminal = jobs.values().stream().filter(ClientMachineWatches::terminal).findFirst().orElse(null);
            if (terminal == null) throw new IllegalStateException("machine_watch_client_capacity");
            jobs.remove(terminal.id);
        }
        jobs.putIfAbsent(id,new Job(id,label,report));
    }
    public static void tick(Minecraft minecraft) {
        bind(minecraft.player);
        if (player == null) return;
        long now = player.level().getGameTime();
        for (Job job : jobs.values()) {
            if (job.request != null) {
                var state = job.request.snapshot();
                if (!state.settled()) continue;
                job.request = null; job.nextPoll = now + 40;
                if (state.status() == ClientRequestReceipt.Status.SUCCEEDED) {
                    job.report = state.result(); notifyChanged(job);
                } else {
                    job.report.addProperty("state","needs_attention"); job.report.addProperty("reason",state.code());
                    job.report.addProperty("monitor_active",false); notifyChanged(job);
                }
            }
            if (terminal(job) && !job.cancel || now < job.nextPoll || job.request != null) continue;
            if (!ServerAssistClient.supported("machine.watch")) continue;
            JsonObject body = new JsonObject(); body.addProperty("action",job.cancel ? "cancel" : "status"); body.addProperty("job_id",job.id.toString());
            job.request = ServerAssistClient.submit("machine.watch",body,false,null); job.cancel = false;
        }
    }
    public static JsonObject cancel(LocalPlayer current, UUID id) {
        bind(current); Job job = jobs.get(id);
        if (job == null) throw new IllegalArgumentException("machine_watch_not_found_in_current_connection");
        job.cancel = true; job.nextPoll = 0;
        JsonObject result = new JsonObject(); result.addProperty("job_id",id.toString()); result.addProperty("cancel_requested",true);
        return result;
    }
    public static JsonArray view(LocalPlayer current) {
        bind(current); JsonArray result = new JsonArray();
        for (Job job : jobs.values()) {
            JsonObject row = job.report.deepCopy(); row.addProperty("label",job.label); row.addProperty("job_id",job.id.toString());
            row.addProperty("uses_player_body",false); row.addProperty("restart_requires_registration",true); result.add(row);
        }
        return result;
    }
    private static void bind(LocalPlayer current) {
        Object currentLevel = current == null ? null : current.level();
        if (current == player && currentLevel == level) return;
        for (Job job : jobs.values()) if (job.request != null) ServerAssistClient.cancel(job.request.id());
        jobs.clear(); player = current; level = currentLevel;
    }
    private static boolean terminal(Job job) {
        String state = text(job.report,"state");
        return state.equals("completed") || state.equals("cancelled")
                || job.report.has("monitor_active") && !job.report.get("monitor_active").getAsBoolean();
    }
    private static void notifyChanged(Job job) {
        String state = text(job.report,"state"), reason = text(job.report,"reason"), key = state + "/" + reason;
        if ((!state.equals("completed") && !state.equals("needs_attention")) || key.equals(job.notified)) return;
        job.notified = key;
        JsonObject data = new JsonObject(); data.addProperty("job_id",job.id.toString()); data.addProperty("label",job.label);
        data.addProperty("state",state); data.addProperty("reason",reason);
        for (String field : new String[]{"native_output","native_delivery_or_direct_output","sink_growth","minimum_output","completed_process_events"})
            if (job.report.has(field)) data.add(field,job.report.get(field).deepCopy());
        IntentRuntime.get().gameEvent(state.equals("completed") ? "machine_production_completed" : "machine_production_attention",
                state.equals("completed") ? job.label + "：产出已经送达，可以安排取货。" : job.label + "：生产需要检查，原因 " + reason, data);
    }
    private static String text(JsonObject json, String key) { return json.has(key) ? json.get(key).getAsString() : ""; }
}
