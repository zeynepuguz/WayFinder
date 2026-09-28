package com.nomi.wayfinder.photo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PhotoStorageTest {

    @TempDir
    Path dir;

    private PhotoStorage storage(String publicBase) {
        return new PhotoStorage(new PhotoProperties(dir.toString(), publicBase, 10, 10, 3, Duration.ofSeconds(60)));
    }

    @Test
    void filesGetARandomKeyAndPublicUrls() throws Exception {
        PhotoStorage storage = storage("https://api.example.com/media/photos/");

        String key = storage.save(new byte[]{1, 2}, new byte[]{3});

        assertThat(key).matches("[0-9a-f]{32}");
        assertThat(storage.readMain(key)).containsExactly(1, 2);
        assertThat(Files.readAllBytes(storage.thumbPath(key))).containsExactly(3);
        assertThat(storage.url(key))
                .isEqualTo("https://api.example.com/media/photos/" + key.substring(0, 2) + "/" + key + ".jpg");
        assertThat(storage.thumbUrl(key)).endsWith("/" + key + "_t.jpg");
        assertThat(storage.url(null)).isNull();
        assertThat(storage.rootLocation()).startsWith("file:").endsWith("/");

        storage.delete(key);

        assertThat(storage.mainPath(key)).doesNotExist();
        assertThat(storage.thumbPath(key)).doesNotExist();
    }

    @Test
    void deleteIgnoresAnythingThatIsNotAKey() throws Exception {
        PhotoStorage storage = storage("/media/photos");
        Path outside = Files.writeString(dir.resolve("keep.txt"), "x");

        storage.delete("../keep");
        storage.delete(null);

        assertThat(outside).exists();
    }

    @Test
    void orphanSweepRemovesOnlyOldUnknownFiles() throws Exception {
        PhotoStorage storage = storage("/media/photos");
        String known = storage.save(new byte[]{1}, new byte[]{1});
        String orphan = storage.save(new byte[]{1}, new byte[]{1});
        String fresh = storage.save(new byte[]{1}, new byte[]{1});
        Instant old = Instant.now().minus(Duration.ofDays(2));
        for (String key : new String[]{known, orphan}) {
            Files.setLastModifiedTime(storage.mainPath(key), FileTime.from(old));
            Files.setLastModifiedTime(storage.thumbPath(key), FileTime.from(old));
        }

        int deleted = storage.deleteOrphans(Set.of(known), Instant.now().minus(Duration.ofDays(1)));

        assertThat(deleted).isEqualTo(2);
        assertThat(storage.mainPath(known)).exists();
        assertThat(storage.mainPath(orphan)).doesNotExist();
        // Uploaded a moment ago, its row may not be committed yet
        assertThat(storage.mainPath(fresh)).exists();
    }
}
