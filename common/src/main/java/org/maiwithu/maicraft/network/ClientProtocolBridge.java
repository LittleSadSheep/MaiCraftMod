// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.network;

import com.google.gson.JsonObject;
import java.util.Objects;

/** Loader seam only. It deliberately has no reference to Minecraft client classes. */
public final class ClientProtocolBridge {
    public interface Transport {
        boolean available();
        void send(JsonObject envelope);
    }

    public interface Listener {
        void connected();
        void received(JsonObject envelope);
        void disconnected();
    }

    private static Transport transport;
    private static Listener listener;

    private ClientProtocolBridge() {}

    public static void installTransport(Transport next) { transport = Objects.requireNonNull(next); }
    public static void installListener(Listener next) { listener = Objects.requireNonNull(next); }
    public static boolean available() { return transport != null && transport.available(); }

    /** False means no send was attempted. A send exception must be reconciled as uncertain. */
    public static boolean send(JsonObject envelope) {
        ProtocolJson.encodeRequest(envelope);
        if (!available()) return false;
        transport.send(envelope.deepCopy());
        return true;
    }

    public static void connected() { if (listener != null) listener.connected(); }

    public static void receive(JsonObject envelope) {
        ProtocolJson.encode(envelope);
        if (listener != null) listener.received(envelope.deepCopy());
    }

    public static void receive(String encoded) {
        JsonObject envelope;
        try { envelope = ProtocolJson.decode(encoded); }
        catch (RuntimeException malformed) { disconnected(); return; }
        receive(envelope);
    }

    public static void disconnected() { if (listener != null) listener.disconnected(); }
}
