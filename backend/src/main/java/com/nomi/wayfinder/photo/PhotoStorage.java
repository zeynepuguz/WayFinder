package com.nomi.wayfinder.photo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Stored photos on disk: {storageDir}/{first 2 chars of key}/{key}.jpg and {key}_t.jpg (thumbnail).
 * Keys are random UUIDs (32 hex chars), never anything the user sent. In production Caddy serves the same
 * directory under /media/photos (deploy/Caddyfile); in development Spring does (WebConfig).
 */
@Component
public class PhotoStorage {

    private static final Logger log = LoggerFactory.getLogger(PhotoStorage.class);
    private static final Pattern KEY = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern FILE = Pattern.compile("([0-9a-f]{32})(_t)?\\.jpg");

    private final Path root;
    private final String publicBase;

    public PhotoStorage(PhotoProperties properties) {
        // An empty variable in .env (PHOTOS_STORAGE_DIR=) must not mean "the working directory"
        String dir = properties.storageDir() == null || properties.storageDir().isBlank()
                ? "./data/photos" : properties.storageDir().trim();
        this.root = Path.of(dir).toAbsolutePath().normalize();
        String base = properties.publicBase() == null || properties.publicBase().isBlank()
                ? "/media/photos" : properties.publicBase().trim();
        this.publicBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            log.warn("Photo storage directory {} could not be created: {}", root, e.getMessage());
        }
    }

    public Path root() {
        return root;
    }

    // "file:/.../photos/" for Spring's resource handler (the trailing slash matters)
    public String rootLocation() {
        String uri = root.toUri().toString();
        return uri.endsWith("/") ? uri : uri + "/";
    }

    /** Writes both files and returns the new key. */
    public String save(byte[] jpeg, byte[] thumbnail) {
        String key = UUID.randomUUID().toString().replace("-", "");
        try {
            Path dir = Files.createDirectories(root.resolve(key.substring(0, 2)));
            write(dir.resolve(key + ".jpg"), jpeg);
            write(dir.resolve(key + "_t.jpg"), thumbnail);
        } catch (IOException e) {
            delete(key);
            throw new UncheckedIOException("Could not store photo", e);
        }
        return key;
    }

    // Temp file + move: a half-written file is never served
    private static void write(Path target, byte[] bytes) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temp, bytes);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public byte[] readMain(String key) throws IOException {
        return Files.readAllBytes(mainPath(key));
    }

    public void delete(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            return;
        }
        for (Path path : new Path[]{mainPath(key), thumbPath(key)}) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                // The orphan sweep (PhotoJobs) tries again
                log.warn("Could not delete photo file {}: {}", path.getFileName(), e.getMessage());
            }
        }
    }

    public String url(String key) {
        return key == null ? null : publicBase + "/" + key.substring(0, 2) + "/" + key + ".jpg";
    }

    public String thumbUrl(String key) {
        return key == null ? null : publicBase + "/" + key.substring(0, 2) + "/" + key + "_t.jpg";
    }

    Path mainPath(String key) {
        return root.resolve(key.substring(0, 2)).resolve(key + ".jpg");
    }

    Path thumbPath(String key) {
        return root.resolve(key.substring(0, 2)).resolve(key + "_t.jpg");
    }

    /**
     * Deletes files that no row points to any more (a place removed by an OSM refresh, a crash between
     * writing the files and inserting the row) and leftover temp files. Only files older than {@code before},
     * so a photo being uploaded right now is never touched.
     *
     * @return number of deleted files
     */
    public int deleteOrphans(Set<String> knownKeys, Instant before) {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        Set<Path> toDelete = new HashSet<>();
        try (Stream<Path> files = Files.walk(root, 2)) {
            files.filter(Files::isRegularFile).forEach(path -> {
                String name = path.getFileName().toString();
                var matcher = FILE.matcher(name);
                boolean orphan = matcher.matches() ? !knownKeys.contains(matcher.group(1)) : name.endsWith(".tmp");
                if (orphan && olderThan(path, before)) {
                    toDelete.add(path);
                }
            });
        } catch (IOException e) {
            log.warn("Photo orphan sweep failed: {}", e.getMessage());
            return 0;
        }
        int deleted = 0;
        for (Path path : toDelete) {
            try {
                if (Files.deleteIfExists(path)) {
                    deleted++;
                }
            } catch (IOException e) {
                log.warn("Could not delete orphan photo file {}: {}", path.getFileName(), e.getMessage());
            }
        }
        return deleted;
    }

    private static boolean olderThan(Path path, Instant before) {
        try {
            return Files.getLastModifiedTime(path).toInstant().isBefore(before);
        } catch (IOException e) {
            return false;
        }
    }
}
