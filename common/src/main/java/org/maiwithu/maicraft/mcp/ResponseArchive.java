package org.maiwithu.maicraft.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.maiwithu.maicraft.core.Constants;

/** 网络层的大回执暂存：默认保留决策摘要，原始观察冻结到压缩文件，随后读取不会重新采样或操作玩家。 */
final class ResponseArchive implements AutoCloseable {
    static final String PREFIX = "maicraft://receipts/";
    static final int INLINE_CHARS = 8000;
    private static final long IDLE_MILLIS = Duration.ofMinutes(30).toMillis();
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private final int capacity;
    private final long byteBudget;
    private final LongSupplier clock;
    private Path directory;
    private long bytes;
    private boolean closed;
    private record Entry(Path file, long bytes, long usedAt) {}

    ResponseArchive() { this(32, 64L << 20, System::currentTimeMillis); }
    ResponseArchive(int capacity, long byteBudget, LongSupplier clock) {
        if (capacity < 1 || byteBudget < 1) throw new IllegalArgumentException("Invalid archive capacity");
        this.capacity = capacity; this.byteBudget = byteBudget; this.clock = clock;
    }

    synchronized JsonElement present(JsonElement value) {
        if (closed || JsonReadback.fits(value, INLINE_CHARS)) return value;
        try {
            String uri = retain(value); JsonObject result = new JsonObject();
            // 接单编号、当前状态、问题和下一次等待参数都优先留在外层；大几何、配方与教材改为可读取的引用。
            if (value.isJsonObject() && value.getAsJsonObject().size() <= 40) {
                for (var field : value.getAsJsonObject().entrySet()) result.add(field.getKey(),
                        JsonReadback.preview(field.getValue(), JsonReadback.childPath("", field.getKey()), 800, path -> link(uri, path, 0, 5)));
            } else result.add("value", JsonReadback.preview(value, "", 3000, path -> link(uri, path, 0, 5)));
            if (!JsonReadback.fits(result, INLINE_CHARS - 400)) {
                JsonObject smaller = new JsonObject();
                for (String key : List.of("task_id", "plan_id", "request_key", "state", "status", "accepted", "success",
                        "wake_reason", "next_attention", "snapshot_id", "target", "ready_to_execute"))
                    if (result.has(key)) smaller.add(key, result.get(key));
                smaller.add("details", JsonReadback.preview(value, "", 2400, path -> link(uri, path, 0, 5)));
                result = smaller;
            }
            result.addProperty("details_uri", uri); result.addProperty("details_temporary", true);
            result.addProperty("partial", true);
            return result;
        } catch (IOException failure) {
            // 暂存失败不能把已接单或已完成的游戏动作报成失败；保留原始回执，让宿主仍能取得确定结果。
            Constants.LOG.warn("[maicraft-mcp] Could not archive a large response", failure);
            return value;
        }
    }

    synchronized JsonObject read(String rawUri) {
        expire(); URI parsed = URI.create(rawUri);
        if (!rawUri.startsWith(PREFIX) || parsed.getFragment() != null) throw new IllegalArgumentException("Invalid receipt URI");
        String id = parsed.getPath().substring(1);
        try { if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid receipt ID"); }
        String path = ""; int offset = 0, limit = 5;
        var seen = new HashSet<String>();
        if (parsed.getRawQuery() != null) for (String pair : parsed.getRawQuery().split("&")) {
            String[] fields = pair.split("=", 2);
            if (fields.length != 2 || !seen.add(fields[0])) throw new IllegalArgumentException("Invalid receipt page");
            String text = URLDecoder.decode(fields[1], StandardCharsets.UTF_8);
            switch (fields[0]) {
                case "path" -> path = text;
                case "offset" -> offset = Integer.parseInt(text);
                case "limit" -> limit = Integer.parseInt(text);
                default -> throw new IllegalArgumentException("Unknown receipt page parameter");
            }
        }
        Entry stored = entries.get(id);
        if (stored == null) throw new IllegalArgumentException("receipt_expired: query task/get or repeat the read-only observation; do not repeat execute to recover a receipt");
        String uri = PREFIX + id;
        try (var reader = new InputStreamReader(new GZIPInputStream(Files.newInputStream(stored.file())), StandardCharsets.UTF_8)) {
            JsonObject page = JsonReadback.page(JsonParser.parseReader(reader), path, offset, limit, child -> link(uri, child, 0, 5));
            entries.remove(id); entries.put(id, new Entry(stored.file(), stored.bytes(), clock.getAsLong()));
            page.addProperty("snapshot_only", true); page.addProperty("details_uri", uri);
            if (page.has("next_offset")) page.addProperty("next_uri", link(uri, path, page.get("next_offset").getAsInt(), limit));
            return page;
        } catch (IOException failure) { throw new IllegalArgumentException("receipt_unavailable: re-read the task or observation", failure); }
    }

    private String retain(JsonElement value) throws IOException {
        expire();
        if (directory == null) directory = Files.createTempDirectory("maicraft-mcp-receipts-");
        String id = UUID.randomUUID().toString(); Path file = directory.resolve(id + ".json.gz");
        try (var writer = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(file)), StandardCharsets.UTF_8)) {
            new Gson().toJson(value, writer);
        } catch (IOException failure) { Files.deleteIfExists(file); throw failure; }
        long length = Files.size(file); bytes += length;
        entries.put(id, new Entry(file, length, clock.getAsLong()));
        // 常规份数和压缩字节都有预算；单份特别大的已完成回执仍保留到下一次归档，不能接单后丢失它的证据。
        while (entries.size() > capacity || bytes > byteBudget && entries.size() > 1) remove(entries.firstEntry().getKey());
        return PREFIX + id;
    }

    private void expire() {
        long before = clock.getAsLong() - IDLE_MILLIS;
        for (String id : entries.keySet().toArray(String[]::new)) if (entries.get(id).usedAt() < before) remove(id);
    }

    private void remove(String id) {
        Entry removed = entries.remove(id); bytes -= removed.bytes();
        try { Files.deleteIfExists(removed.file()); }
        catch (IOException failure) { Constants.LOG.warn("[maicraft-mcp] Could not remove an expired receipt", failure); }
    }

    static String link(String uri, String path, int offset, int limit) {
        return uri + "?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8) + "&offset=" + offset + "&limit=" + limit;
    }

    synchronized void reopen() { closed = false; }

    @Override public synchronized void close() {
        // 只清理本服务创建的临时文件；游戏任务和世界检查点不属于回执缓存。
        closed = true;
        for (String id : entries.keySet().toArray(String[]::new)) remove(id);
        if (directory != null) try { Files.deleteIfExists(directory); directory = null; }
        catch (IOException failure) { Constants.LOG.warn("[maicraft-mcp] Could not remove receipt directory", failure); }
    }
}
