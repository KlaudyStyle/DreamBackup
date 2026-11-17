package io.github.klaudystyle.dreambackup.storage;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ZipBackupStore {
    private static final String ARCHIVE_PREFIX = "DreamBackup-";
    private static final Pattern ARCHIVE_NAME = Pattern.compile(
            ARCHIVE_PREFIX + "[0-9]{8}-[0-9]{6}-[0-9]{3}-[0-9a-f]{8}"
                    + "(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.zip");
    private static final int BUFFER_SIZE = 64 * 1024;

    private final Path outputDirectory;
    private final int retention;

    public ZipBackupStore(Path outputDirectory, int retention) {
        this.outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory")
                .toAbsolutePath().normalize();
        if (retention < 0) {
            throw new IllegalArgumentException("Retention cannot be negative");
        }
        this.retention = retention;
    }

    public synchronized Path create(List<BackupSource> sources) throws IOException {
        checkInterrupted();
        Path output = openOutputDirectory(true);
        Path temporary = Files.createTempFile(output, ".dreambackup-", ".part");
        try {
            try (ZipOutputStream archive = new ZipOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)))) {
                ArchiveWriter writer = new ArchiveWriter(archive, output);
                for (BackupSource source : sources) {
                    writer.add(source);
                }
                if (writer.entries.isEmpty()) {
                    throw new IOException("No backup sources were found");
                }
            }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                channel.force(true);
            }
            checkInterrupted();
            verifyOutputDirectory(output);
            return Files.move(temporary, output.resolve(newArchiveName()), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                verifyOutputDirectory(output);
                Files.deleteIfExists(temporary);
            } catch (IOException | RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public synchronized void prune() throws IOException {
        if (retention == 0) {
            return;
        }
        Path output = openOutputDirectory(false);
        if (output == null) {
            return;
        }
        List<Path> archives = new ArrayList<Path>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(output)) {
            for (Path child : children) {
                if (ARCHIVE_NAME.matcher(child.getFileName().toString()).matches()
                        && Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                    archives.add(child);
                }
            }
        }
        Collections.sort(archives, Collections.reverseOrder());
        for (int index = retention; index < archives.size(); index++) {
            checkInterrupted();
            verifyOutputDirectory(output);
            Path archive = archives.get(index);
            if (Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
                Files.deleteIfExists(archive);
            }
        }
    }

    private void verifyOutputDirectory(Path expected) throws IOException {
        if (!expected.equals(openOutputDirectory(false))) {
            throw new IOException("Archive directory changed during backup: " + outputDirectory);
        }
    }

    private Path openOutputDirectory(boolean create) throws IOException {
        Path current = outputDirectory.getRoot();
        for (Path component : outputDirectory) {
            current = current.resolve(component);
            BasicFileAttributes attributes;
            try {
                attributes = readAttributes(current);
            } catch (NoSuchFileException missing) {
                if (!create) {
                    return null;
                }
                try {
                    Files.createDirectory(current);
                } catch (FileAlreadyExistsException concurrentCreation) {
                    if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                        throw concurrentCreation;
                    }
                }
                attributes = readAttributes(current);
            }
            if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
                throw new IOException("Archive directory uses a link or is not a directory: " + current);
            }
        }
        return requireUnlinkedPath(outputDirectory);
    }

    private static Path requireUnlinkedPath(Path path) throws IOException {
        Path physical = path.toRealPath();
        if (!physical.equals(path.toRealPath(LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Path uses a filesystem link: " + path);
        }
        return physical;
    }

    private static BasicFileAttributes readAttributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static String newArchiveName() {
        SimpleDateFormat timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT);
        timestamp.setTimeZone(TimeZone.getTimeZone("UTC"));
        return ARCHIVE_PREFIX + timestamp.format(new Date()) + "-" + UUID.randomUUID() + ".zip";
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Backup interrupted");
        }
    }

    private static Path physicalSource(BackupSource source) throws IOException {
        Path path = source.getPath();
        Path current = path.getRoot();
        try {
            for (Path component : path) {
                current = current.resolve(component);
                BasicFileAttributes attributes = readAttributes(current);
                if (attributes.isSymbolicLink() || attributes.isOther()) {
                    throw new IOException("Backup source uses a link or unsupported file: " + current);
                }
                if (!current.equals(path) && !attributes.isDirectory()) {
                    throw new IOException("Backup source parent is not a directory: " + current);
                }
            }
            return requireUnlinkedPath(path);
        } catch (NoSuchFileException missing) {
            if (source.isRequired()) {
                throw new IOException("Required backup source is missing: " + path, missing);
            }
            return null;
        }
    }

    private static final class ArchiveWriter extends SimpleFileVisitor<Path> {
        private final ZipOutputStream archive;
        private final Path outputDirectory;
        private final byte[] buffer = new byte[BUFFER_SIZE];
        private final Map<String, Entry> entries = new HashMap<String, Entry>();

        private Path sourcePath;
        private String archiveRoot;

        private ArchiveWriter(ZipOutputStream archive, Path outputDirectory) {
            this.archive = archive;
            this.outputDirectory = outputDirectory;
        }

        private void add(BackupSource source) throws IOException {
            checkInterrupted();
            Path physical = physicalSource(source);
            if (physical == null) {
                return;
            }
            if (physical.startsWith(outputDirectory)) {
                throw new IOException("Backup source is inside the archive directory: " + physical);
            }
            sourcePath = physical;
            archiveRoot = source.getArchiveRoot();
            Files.walkFileTree(sourcePath, this);
        }

        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
            checkInterrupted();
            if (directory.equals(outputDirectory) || !directory.equals(directory.toRealPath())) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            writeEntry(entryName(directory), attributes, true);
            archive.closeEntry();
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
            checkInterrupted();
            if (!attributes.isRegularFile()) {
                return FileVisitResult.CONTINUE;
            }

            writeEntry(entryName(file), attributes, false);
            long copied = 0;
            try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    checkInterrupted();
                    archive.write(buffer, 0, count);
                    copied += count;
                }
            }
            BasicFileAttributes current = readAttributes(file);
            if (!current.isRegularFile() || copied != attributes.size() || current.size() != attributes.size()
                    || !current.lastModifiedTime().equals(attributes.lastModifiedTime())
                    || !Objects.equals(current.fileKey(), attributes.fileKey())) {
                throw new IOException("File changed during backup: " + file);
            }
            archive.closeEntry();
            return FileVisitResult.CONTINUE;
        }

        private String entryName(Path path) throws IOException {
            if (path.equals(sourcePath)) {
                return archiveRoot;
            }
            StringBuilder name = new StringBuilder(archiveRoot);
            for (Path component : sourcePath.relativize(path)) {
                String segment = component.toString();
                if (!BackupSource.isSafeComponent(segment)) {
                    throw new IOException("File has an unsafe archive path: " + path);
                }
                name.append('/').append(segment);
            }
            return name.toString();
        }

        private void writeEntry(String name, BasicFileAttributes attributes, boolean directory) throws IOException {
            int slash = name.lastIndexOf('/');
            if (slash >= 0) {
                register(name.substring(0, slash), EntryType.IMPLICIT_DIRECTORY);
            }
            register(name, directory ? EntryType.DIRECTORY : EntryType.FILE);
            ZipEntry entry = new ZipEntry(directory ? name + "/" : name);
            entry.setTime(attributes.lastModifiedTime().toMillis());
            archive.putNextEntry(entry);
        }

        private void register(String name, EntryType type) throws IOException {
            String key = Normalizer.normalize(name, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
            Entry existing = entries.get(key);
            if (existing == null) {
                if (type == EntryType.IMPLICIT_DIRECTORY) {
                    int slash = name.lastIndexOf('/');
                    if (slash >= 0) {
                        register(name.substring(0, slash), EntryType.IMPLICIT_DIRECTORY);
                    }
                }
                entries.put(key, new Entry(name, type));
                return;
            }
            if (existing.name.equals(name)) {
                if (type == EntryType.IMPLICIT_DIRECTORY && existing.type != EntryType.FILE) {
                    return;
                }
                if (type == EntryType.DIRECTORY && existing.type == EntryType.IMPLICIT_DIRECTORY) {
                    entries.put(key, new Entry(name, type));
                    return;
                }
            }
            throw new IOException("Conflicting paths in backup archive: " + existing.name + " and " + name);
        }
    }

    private enum EntryType {
        FILE,
        DIRECTORY,
        IMPLICIT_DIRECTORY
    }

    private static final class Entry {
        private final String name;
        private final EntryType type;

        private Entry(String name, EntryType type) {
            this.name = name;
            this.type = type;
        }
    }
}
