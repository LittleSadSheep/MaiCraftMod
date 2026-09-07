// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.preview;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Small loader-independent client configuration. Dev is opt-in and never alters server config. */
public final class PreviewConfig {
    private static Path file;
    private static boolean enabled;
    private PreviewConfig() {}

    static void load(Path gameDirectory) {
        file = gameDirectory.resolve("config/maicraft-preview.properties");
        Properties values = new Properties();
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file)) { values.load(reader); }
            catch (IOException | IllegalArgumentException ignored) { enabled = false; return; }
        }
        enabled = Boolean.parseBoolean(values.getProperty("devMode", "false"));
    }

    static boolean enabled() { return enabled; }
    public static boolean enabled(Path gameDirectory) {
        if (file == null) load(gameDirectory);
        return enabled;
    }
    static void enabled(boolean value) throws IOException {
        enabled = value;
        if (file == null) return;
        Files.createDirectories(file.getParent());
        Properties values = new Properties();
        values.setProperty("devMode", Boolean.toString(value));
        try (Writer writer = Files.newBufferedWriter(file)) {
            values.store(writer, "MaiCraft client blueprint review; /maicraft Dev on|off");
        }
    }
}
