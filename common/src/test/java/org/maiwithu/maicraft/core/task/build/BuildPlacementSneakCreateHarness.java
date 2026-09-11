// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.jar.JarFile;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import sun.misc.Unsafe;

/** Test-only native loading and inert player bridge; no game boot, packet sender or production Mixin application. */
final class BuildPlacementSneakCreateHarness implements AutoCloseable {
    private static final String MOTOR = "com.simibubi.create.content.kinetics.motor.CreativeMotorBlock";
    final URLClassLoader loader;
    final Block motor;
    final BlockItem item;

    BuildPlacementSneakCreateHarness(Path suppliedJar) throws Exception {
        Path jar = suppliedJar.toRealPath();
        Path dependencyRoot = Path.of("docs", "tmp", "build-placement-sneak-create", "deps").toAbsolutePath().normalize();
        Files.createDirectories(dependencyRoot);
        List<URL> urls = new ArrayList<>(); urls.add(jar.toUri().toURL());
        extractDependencies(jar, dependencyRoot, urls, 0);
        loader = new URLClassLoader(urls.toArray(URL[]::new), BuildPlacementSneakCreateHarness.class.getClassLoader());
        try {
            Class<?> type = Class.forName(MOTOR, true, loader);
            Path actual = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!Files.isSameFile(actual, jar)) throw new AssertionError("CreativeMotorBlock did not load from supplied jar: " + actual);
            motor = constructBlock(type);
            item = constructItem(motor);
        } catch (Exception | LinkageError failure) { loader.close(); throw failure; }
    }

    static FixturePlayer player(LocalPlayer template, boolean actualSneak) throws Exception {
        Unsafe unsafe = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        FixturePlayer player = (FixturePlayer) unsafe.allocateInstance(FixturePlayer.class);
        // Both objects belong to this standalone test. Copy inert state, never change the original fixture player.
        for (Class<?> type = LocalPlayer.class; type != Object.class; type = type.getSuperclass()) {
            for (Field member : type.getDeclaredFields()) {
                if (Modifier.isStatic(member.getModifiers())) continue;
                member.setAccessible(true); member.set(player, member.get(template));
            }
        }
        player.input = new Input(); player.input.shiftKeyDown = actualSneak;
        return player;
    }

    static final class FixturePlayer extends LocalPlayer {
        private FixturePlayer() { super(null, null, null, null, null, false, false); }
        @Override public boolean isShiftKeyDown() {
            // Explicit test bridge: validates the shared helper with real Create bytecode, not Mixin transformation.
            return PlacementSneakProjection.project(this, super.isShiftKeyDown());
        }
    }

    private static Block constructBlock(Class<?> type) throws Exception {
        Object registry = BuiltInRegistries.BLOCK;
        Field holders = field(MappedRegistry.class, "unregisteredIntrusiveHolders"), frozen = field(MappedRegistry.class, "frozen");
        Object oldHolders = holders.get(registry); boolean oldFrozen = frozen.getBoolean(registry);
        try {
            holders.set(registry, new IdentityHashMap<>()); frozen.setBoolean(registry, false);
            return (Block) type.getConstructor(BlockBehaviour.Properties.class).newInstance(BlockBehaviour.Properties.of().dynamicShape());
        } finally { holders.set(registry, oldHolders); frozen.setBoolean(registry, oldFrozen); }
    }

    private static BlockItem constructItem(Block block) throws Exception {
        Object registry = BuiltInRegistries.ITEM;
        Field holders = field(MappedRegistry.class, "unregisteredIntrusiveHolders"), frozen = field(MappedRegistry.class, "frozen");
        Object oldHolders = holders.get(registry); boolean oldFrozen = frozen.getBoolean(registry);
        try {
            holders.set(registry, new IdentityHashMap<>()); frozen.setBoolean(registry, false);
            return new BlockItem(block, new Item.Properties());
        } finally { holders.set(registry, oldHolders); frozen.setBoolean(registry, oldFrozen); }
    }

    private static void extractDependencies(Path jar, Path root, List<URL> urls, int depth) throws IOException {
        if (depth > 4) throw new IOException("Embedded Create dependency depth exceeded");
        try (JarFile source = new JarFile(jar.toFile())) {
            var entries = source.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith("META-INF/jarjar/") || !entry.getName().endsWith(".jar")) continue;
                if (urls.size() >= 32 || entry.getSize() < 0 || entry.getSize() > 64_000_000) throw new IOException("Embedded Create dependency budget exceeded");
                String name = entry.getName().substring(entry.getName().lastIndexOf('/') + 1);
                if (!name.matches("[A-Za-z0-9._+\\-]+\\.jar")) throw new IOException("Invalid embedded jar filename");
                Path destination = root.resolve(urls.size() + "-" + name).normalize();
                if (!destination.startsWith(root) || !destination.getParent().equals(root)) throw new IOException("Embedded dependency escapes test directory");
                try (var input = source.getInputStream(entry)) { Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING); }
                urls.add(destination.toUri().toURL());
                extractDependencies(destination, root, urls, depth + 1);
            }
        }
    }

    static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            try { Field field = owner.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException missing) { /* Continue through the inert fixture hierarchy. */ }
        }
        throw new NoSuchFieldException(name);
    }
    @Override public void close() throws IOException { loader.close(); }
}
