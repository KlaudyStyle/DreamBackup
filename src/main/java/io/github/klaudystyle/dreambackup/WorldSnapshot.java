package io.github.klaudystyle.dreambackup;

import io.github.klaudystyle.dreambackup.storage.BackupSource;
import net.minecraft.server.v1_6_R3.ExceptionWorldConflict;
import net.minecraft.server.v1_6_R3.FileIOThread;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.craftbukkit.v1_6_R3.CraftWorld;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class WorldSnapshot implements Closeable {
    private final Path staging;
    private final List<BackupSource> sources = new ArrayList<BackupSource>();

    private WorldSnapshot(Path staging) {
        this.staging = staging;
    }

    static WorldSnapshot capture(Server server, Path outputDirectory) throws IOException {
        Path worldContainer = server.getWorldContainer().toPath().toAbsolutePath().normalize();
        Map<Path, World> worlds = new LinkedHashMap<Path, World>();
        for (World world : server.getWorlds()) {
            Path folder = world.getWorldFolder().toPath().toRealPath();
            if (!Files.isDirectory(folder) || folder.startsWith(outputDirectory)) {
                throw new IOException("Invalid world folder for backup: " + folder);
            }
            if (worlds.put(folder, world) != null) {
                throw new IOException("Multiple loaded worlds share the folder " + folder);
            }
        }
        if (worlds.isEmpty()) {
            throw new IOException("There are no loaded worlds to save");
        }

        WorldSnapshot snapshot = new WorldSnapshot(Files.createTempDirectory("dreambackup-").toRealPath());
        try {
            server.savePlayers();
            for (World world : worlds.values()) {
                save(world);
            }
            FileIOThread.a.a();

            for (Map.Entry<Path, World> entry : worlds.entrySet()) {
                Path folder = entry.getKey();
                Path worldPath = entry.getValue().getWorldFolder().toPath().toAbsolutePath().normalize();
                Path suffix = worldPath.startsWith(worldContainer) && !worldPath.equals(worldContainer)
                        ? worldContainer.relativize(worldPath) : worldPath.getFileName();
                if (suffix == null) {
                    throw new IOException("World folder has no name: " + folder);
                }
                snapshot.stage(folder, archiveRoot(suffix), outputDirectory, worlds.keySet());
            }
            return snapshot;
        } catch (IOException | RuntimeException | Error failure) {
            try {
                snapshot.close();
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static void save(World world) throws IOException {
        boolean autoSave = world.isAutoSave();
        try {
            world.setAutoSave(true);
            ((CraftWorld) world).getHandle().save(true, null);
        } catch (ExceptionWorldConflict failure) {
            throw new IOException("Could not save world " + world.getName(), failure);
        } finally {
            world.setAutoSave(autoSave);
        }
    }

    private static String archiveRoot(Path worldPath) {
        StringBuilder name = new StringBuilder("worlds");
        for (Path component : worldPath) {
            name.append('/').append(component);
        }
        return name.toString();
    }

    private void stage(final Path folder, String archiveRoot, final Path outputDirectory,
                       final Set<Path> worldFolders) throws IOException {
        if (staging.startsWith(folder)) {
            throw new IOException("The system temporary directory is inside a world folder: " + folder);
        }
        final Path destination = Files.createDirectory(staging.resolve(Integer.toString(sources.size())));
        BackupSource source = new BackupSource(archiveRoot, destination, true);
        Files.walkFileTree(folder, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (directory.equals(outputDirectory) || !directory.equals(directory.toRealPath())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!directory.equals(folder)) {
                    if (worldFolders.contains(directory)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Files.createDirectory(destination.resolve(folder.relativize(directory)));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (attributes.isRegularFile() && !file.equals(folder.resolve("session.lock"))) {
                    Path copy = Files.copy(file, destination.resolve(folder.relativize(file)),
                            LinkOption.NOFOLLOW_LINKS);
                    Files.getFileAttributeView(copy, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                            .setTimes(attributes.lastModifiedTime(), null, null);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        sources.add(source);
    }

    List<BackupSource> getSources() {
        return sources;
    }

    @Override
    public void close() throws IOException {
        Files.walkFileTree(staging, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
