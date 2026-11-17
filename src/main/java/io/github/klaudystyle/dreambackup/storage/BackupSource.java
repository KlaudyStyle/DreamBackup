package io.github.klaudystyle.dreambackup.storage;

import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

public final class BackupSource {
    private static final Pattern WINDOWS_DEVICE = Pattern.compile(
            "CON|PRN|AUX|NUL|COM[1-9\u00b9\u00b2\u00b3]|LPT[1-9\u00b9\u00b2\u00b3]|CONIN\\$|CONOUT\\$",
            Pattern.CASE_INSENSITIVE);

    private final String archiveRoot;
    private final Path path;
    private final boolean required;

    public BackupSource(String archiveRoot, Path path, boolean required) {
        Objects.requireNonNull(archiveRoot, "archiveRoot");
        for (String component : archiveRoot.split("/", -1)) {
            if (!isSafeComponent(component)) {
                throw new IllegalArgumentException("Invalid archive root: " + archiveRoot);
            }
        }
        this.archiveRoot = archiveRoot;
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.required = required;
    }

    public String getArchiveRoot() {
        return archiveRoot;
    }

    public Path getPath() {
        return path;
    }

    public boolean isRequired() {
        return required;
    }

    static boolean isSafeComponent(String name) {
        if (name.isEmpty() || name.endsWith(".") || name.endsWith(" ")) {
            return false;
        }
        int extension = name.indexOf('.');
        String base = extension < 0 ? name : name.substring(0, extension);
        if (WINDOWS_DEVICE.matcher(base.trim()).matches()) {
            return false;
        }
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if (Character.isISOControl(character) || "\\/:<>\"|?*".indexOf(character) >= 0) {
                return false;
            }
        }
        return true;
    }
}
