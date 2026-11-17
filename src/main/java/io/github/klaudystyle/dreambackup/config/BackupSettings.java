package io.github.klaudystyle.dreambackup.config;

import io.github.klaudystyle.dreambackup.storage.BackupSource;
import org.bukkit.configuration.file.FileConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class BackupSettings {
    private static final long TICKS_PER_MINUTE = 20L * 60;

    private final Path outputDirectory;
    private final int retention;
    private final long intervalTicks;
    private final List<BackupSource> additionalSources;

    private BackupSettings(Path outputDirectory, int retention, long intervalTicks,
                           List<BackupSource> additionalSources) {
        this.outputDirectory = outputDirectory;
        this.retention = retention;
        this.intervalTicks = intervalTicks;
        this.additionalSources = Collections.unmodifiableList(additionalSources);
    }

    public static BackupSettings load(FileConfiguration config, Path serverRoot, Path pluginDataDirectory) {
        Path root = serverRoot.toAbsolutePath().normalize();
        Path dataDirectory = pluginDataDirectory.toAbsolutePath().normalize();
        Path output = readOutputDirectory(config, dataDirectory);
        int retention = readNonnegativeInteger(config, "retention");
        long interval = readNonnegativeInteger(config, "interval-minutes") * TICKS_PER_MINUTE;

        List<BackupSource> sources = new ArrayList<BackupSource>();
        Path pluginsDirectory = dataDirectory.getParent();
        readSources(config, "paths", false, root, pluginsDirectory, output, sources);
        readSources(config, "required-paths", true, root, pluginsDirectory, output, sources);
        return new BackupSettings(output, retention, interval, sources);
    }

    public Path getOutputDirectory() {
        return outputDirectory;
    }

    public int getRetention() {
        return retention;
    }

    public long getIntervalTicks() {
        return intervalTicks;
    }

    public List<BackupSource> getAdditionalSources() {
        return additionalSources;
    }

    private static Path readOutputDirectory(FileConfiguration config, Path pluginDataDirectory) {
        Object value = config.get("output-directory");
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new IllegalArgumentException("output-directory must be a directory path.");
        }

        Path configured = pluginDataDirectory.getFileSystem().getPath((String) value);
        if (configured.isAbsolute()) {
            Path output = configured.normalize();
            if (output.getParent() == null) {
                throw new IllegalArgumentException("output-directory must be below a filesystem root.");
            }
            return output;
        }

        Path output = pluginDataDirectory.resolve(configured).normalize();
        if (!output.startsWith(pluginDataDirectory) || output.equals(pluginDataDirectory)) {
            throw new IllegalArgumentException("A relative output-directory must be below the plugin data directory.");
        }
        return output;
    }

    private static int readNonnegativeInteger(FileConfiguration config, String key) {
        Object value = config.get(key);
        if (value instanceof Integer || value instanceof Long) {
            long number = ((Number) value).longValue();
            if (number >= 0 && number <= Integer.MAX_VALUE) {
                return (int) number;
            }
        }
        throw new IllegalArgumentException(key + " must be a nonnegative integer up to " + Integer.MAX_VALUE + ".");
    }

    private static void readSources(FileConfiguration config, String key, boolean required, Path serverRoot,
                                    Path pluginsDirectory, Path output, List<BackupSource> sources) {
        Object value = config.get(key);
        if (!(value instanceof List<?>)) {
            throw new IllegalArgumentException(key + " must be a list of server-relative paths.");
        }

        for (Object entry : (List<?>) value) {
            if (!(entry instanceof String)) {
                throw new IllegalArgumentException(key + " entries must be server-relative paths.");
            }

            String path = ((String) entry).replace('\\', '/');
            for (String component : path.split("/", -1)) {
                validatePathComponent(key, path, component);
            }

            Path sourcePath = path.equals("plugins") ? pluginsDirectory : serverRoot.resolve(path);
            if (sourcePath == null || sourcePath.getParent() == null) {
                throw new IllegalArgumentException("The plugins directory must be below a filesystem root.");
            }
            if (sourcePath.startsWith(output)) {
                throw new IllegalArgumentException("output-directory contains the backup source '" + path + "'.");
            }

            addSource(sources, new BackupSource("server/" + path, sourcePath, required));
        }
    }

    private static void addSource(List<BackupSource> sources, BackupSource source) {
        String archiveRoot = source.getArchiveRoot().toLowerCase(Locale.ROOT);
        for (int index = 0; index < sources.size(); index++) {
            BackupSource existing = sources.get(index);
            if (source.isRequired() && !existing.isRequired() && source.getArchiveRoot().equals(existing.getArchiveRoot())) {
                sources.set(index, source);
                return;
            }

            String existingRoot = existing.getArchiveRoot().toLowerCase(Locale.ROOT);
            if (existingRoot.equals(archiveRoot) || existingRoot.startsWith(archiveRoot + "/")
                    || archiveRoot.startsWith(existingRoot + "/")) {
                throw new IllegalArgumentException("Overlapping backup source '" + source.getPath() + "'.");
            }
        }
        sources.add(source);
    }

    private static void validatePathComponent(String key, String path, String component) {
        if (component.isEmpty() || component.endsWith(".") || component.endsWith(" ")) {
            throw new IllegalArgumentException(key + " contains an invalid path '" + path + "'.");
        }

        for (int i = 0; i < component.length(); i++) {
            char character = component.charAt(i);
            if (Character.isISOControl(character) || "<>:\"|?*".indexOf(character) >= 0) {
                throw new IllegalArgumentException(key + " contains an invalid path '" + path + "'.");
            }
        }
    }
}
