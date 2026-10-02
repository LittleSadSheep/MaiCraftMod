package org.maiwithu.maicraft.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import java.util.Base64;
import java.util.function.LongSupplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.maiwithu.maicraft.core.Constants;
import org.maiwithu.maicraft.intent.persistence.MemoryDatabase;

/** 网络层的大回执完整交付并冻结到会话临时 SQLite；仍按压缩字节计费，读取不重新采样或操作玩家。 */
final class ResponseArchive implements AutoCloseable {
    static final String PREFIX = "maicraft://receipts/";
    static final int INLINE_CHARS = 8000;
    private static final long IDLE_MILLIS = Duration.ofMinutes(30).toMillis();
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private final int capacity;
    private final long byteBudget;
    private final LongSupplier clock;
    private Path directory;
    private MemoryDatabase database;
    private long bytes;
    private boolean closed;
    private record Entry(long bytes, long usedAt) {}

    ResponseArchive() { this(32, 64L << 20, System::currentTimeMillis); }
    ResponseArchive(int capacity, long byteBudget, LongSupplier clock) {
        if (capacity < 1 || byteBudget < 1) throw new IllegalArgumentException("Invalid archive capacity");
        this.capacity = capacity; this.byteBudget = byteBudget; this.clock = clock;
    }

    synchronized JsonElement present(JsonElement value) {
        if (closed || JsonReadback.fits(value, INLINE_CHARS)) return value;
        try {
            String uri = retain(value);
            // 归档用于冻结诊断原件；已整理的游戏事实完整交付，不能再次按固定字符数吞掉材料和运行状态。
            JsonObject result = value.isJsonObject() ? value.getAsJsonObject().deepCopy() : new JsonObject();
            if (!value.isJsonObject()) result.add("value", value.deepCopy());
            result.addProperty("details_uri", uri); result.addProperty("details_temporary", true);
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
        // 首次读取一次交付选中的完整证据，仍须拒绝非法页码，不能因跳过分页而接受错误参数。
        // 拒绝消息与 JsonReadback.page 同一口径：点名两种计数单位，模型才能一次改对参数。
        if (offset < 0 || limit < 1 || limit > 50) throw new IllegalArgumentException(
                "detail offset must be non-negative and detail limit must be 1..50 collection entries; text pages contain up to 4000 UTF-16 code units regardless of limit. Copy next_offset or next_uri.");
        Entry stored = entries.get(id);
        if (stored == null) throw new IllegalArgumentException("receipt_expired: query task/get or repeat the read-only observation; do not repeat execute to recover a receipt");
        String uri = PREFIX + id;
        try (var reader = new InputStreamReader(new GZIPInputStream(new ByteArrayInputStream(compressed(id))), StandardCharsets.UTF_8)) {
            JsonElement storedValue = JsonParser.parseReader(reader);
            JsonObject page = offset == 0 ? JsonReadback.complete(storedValue, path)
                    : JsonReadback.page(storedValue, path, offset, limit, child -> link(uri, child, 0, 5));
            entries.remove(id); entries.put(id, new Entry(stored.bytes(), clock.getAsLong()));
            page.addProperty("snapshot_only", true); page.addProperty("details_uri", uri);
            if (page.has("next_offset")) page.addProperty("next_uri", link(uri, path, page.get("next_offset").getAsInt(), limit));
            return page;
        } catch (IOException failure) { throw new IllegalArgumentException("receipt_unavailable: re-read the task or observation", failure); }
    }

    private String retain(JsonElement value) throws IOException {
        expire();
        if (directory == null) {
            directory = Files.createTempDirectory("maicraft-mcp-receipts-");
            database = new MemoryDatabase(directory.resolve(MemoryDatabase.FILE_NAME));
        }
        String id = UUID.randomUUID().toString(); var buffer = new ByteArrayOutputStream();
        try (var writer = new OutputStreamWriter(new GZIPOutputStream(buffer), StandardCharsets.UTF_8)) {
            new Gson().toJson(value, writer);
        }
        // 回执只在本次服务会话内有效，使用独立临时数据库；归档提交后才发布可读取的 URI。
        byte[] compressed = buffer.toByteArray();
        if (!database.writeRecord("receipts", "session", id, Base64.getEncoder().encodeToString(compressed), true))
            throw new IOException("receipt_id_conflict");
        long length = compressed.length; bytes += length;
        entries.put(id, new Entry(length, clock.getAsLong()));
        // 常规份数和压缩字节都有预算；单份特别大的已完成回执仍保留到下一次归档，不能接单后丢失它的证据。
        while (entries.size() > capacity || bytes > byteBudget && entries.size() > 1) remove(entries.firstEntry().getKey());
        return PREFIX + id;
    }

    private byte[] compressed(String id) throws IOException {
        String encoded = database.readRecord("receipts", "session", id, Integer.MAX_VALUE);
        if (encoded == null) throw new IOException("receipt_record_missing");
        try { return Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException invalid) { throw new IOException("receipt_record_corrupt", invalid); }
    }

    private void expire() {
        long before = clock.getAsLong() - IDLE_MILLIS;
        for (String id : entries.keySet().toArray(String[]::new)) if (entries.get(id).usedAt() < before) remove(id);
    }

    private void remove(String id) {
        Entry removed = entries.remove(id); bytes -= removed.bytes();
        try { database.deleteRecord("receipts", "session", id); }
        catch (IOException failure) { Constants.LOG.warn("[maicraft-mcp] Could not remove an expired receipt", failure); }
    }

    static String link(String uri, String path, int offset, int limit) {
        return uri + "?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8) + "&offset=" + offset + "&limit=" + limit;
    }

    synchronized void reopen() { closed = false; }

    @Override public synchronized void close() {
        // 只清理本服务创建的会话临时数据库；游戏任务和消费预约位于另一份长期数据库。
        closed = true;
        for (String id : entries.keySet().toArray(String[]::new)) remove(id);
        if (directory != null) try {
            Files.deleteIfExists(directory.resolve(MemoryDatabase.FILE_NAME));
            Files.deleteIfExists(directory); directory = null; database = null;
        }
        catch (IOException failure) { Constants.LOG.warn("[maicraft-mcp] Could not remove receipt directory", failure); }
    }
}
