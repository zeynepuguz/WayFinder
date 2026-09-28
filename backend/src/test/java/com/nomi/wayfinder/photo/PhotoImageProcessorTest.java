package com.nomi.wayfinder.photo;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.nomi.wayfinder.photo.PhotoImageProcessor.Format;
import com.nomi.wayfinder.photo.PhotoImageProcessor.PhotoMetadata;
import com.nomi.wayfinder.photo.PhotoImageProcessor.ProcessedPhoto;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class PhotoImageProcessorTest {

    private static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");

    private final PhotoImageProcessor processor = new PhotoImageProcessor(TimeZone.getTimeZone(ISTANBUL));

    @Test
    void typeIsSniffedFromTheBytesNotTheName() {
        assertThat(PhotoImageProcessor.sniff(TestImages.jpeg(10, 10))).isEqualTo(Format.JPEG);
        assertThat(PhotoImageProcessor.sniff(TestImages.png(10, 10))).isEqualTo(Format.PNG);
        assertThat(PhotoImageProcessor.sniff(ascii("RIFF\0\0\0\0WEBPVP8 "))).isEqualTo(Format.WEBP);
        assertThat(PhotoImageProcessor.sniff(ascii("\0\0\0\u0018ftypheic\0\0\0\0"))).isEqualTo(Format.HEIF);
        assertThat(PhotoImageProcessor.sniff(ascii("\0\0\0\u0018ftypmif1\0\0\0\0"))).isEqualTo(Format.HEIF);
        assertThat(PhotoImageProcessor.sniff(ascii("GIF89a......"))).isEqualTo(Format.UNKNOWN);
        assertThat(PhotoImageProcessor.sniff(ascii("<svg xmlns="))).isEqualTo(Format.UNKNOWN);
        assertThat(PhotoImageProcessor.sniff(new byte[3])).isEqualTo(Format.UNKNOWN);
    }

    @Test
    void heicAndNonImagesAreUnsupported() {
        assertThatThrownBy(() -> processor.process(ascii("\0\0\0\u0018ftypheic\0\0\0\0mif1heic....")))
                .isInstanceOfSatisfying(PhotoRejectedException.class,
                        e -> assertThat(e.getReason()).isEqualTo(RejectReason.UNSUPPORTED));
        assertThatThrownBy(() -> processor.process("%PDF-1.7 not a photo".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOfSatisfying(PhotoRejectedException.class,
                        e -> assertThat(e.getReason()).isEqualTo(RejectReason.UNSUPPORTED));
        // A JPEG header followed by garbage
        byte[] truncated = new byte[64];
        truncated[0] = (byte) 0xFF;
        truncated[1] = (byte) 0xD8;
        truncated[2] = (byte) 0xFF;
        assertThatThrownBy(() -> processor.process(truncated)).isInstanceOf(PhotoRejectedException.class);
    }

    @Test
    void shorterSideBelow600IsTooSmall() {
        assertThatThrownBy(() -> processor.process(TestImages.jpeg(1200, 599)))
                .isInstanceOfSatisfying(PhotoRejectedException.class,
                        e -> assertThat(e.getReason()).isEqualTo(RejectReason.TOO_SMALL));
        assertThat(processor.process(TestImages.jpeg(800, 600)).width()).isEqualTo(800);
    }

    @Test
    void largePhotoIsScaledTo1600WithA480Thumbnail() throws Exception {
        ProcessedPhoto photo = processor.process(TestImages.jpeg(4000, 3000));

        assertThat(photo.width()).isEqualTo(1600);
        assertThat(photo.height()).isEqualTo(1200);
        BufferedImage stored = ImageIO.read(new ByteArrayInputStream(photo.jpeg()));
        BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(photo.thumbnail()));
        assertThat(stored.getWidth()).isEqualTo(1600);
        assertThat(thumb.getWidth()).isEqualTo(480);
        assertThat(thumb.getHeight()).isEqualTo(360);
        assertThat(PhotoImageProcessor.sniff(photo.jpeg())).isEqualTo(Format.JPEG);
    }

    @Test
    void pngIsReEncodedAsJpeg() {
        ProcessedPhoto photo = processor.process(TestImages.png(900, 700));

        assertThat(PhotoImageProcessor.sniff(photo.jpeg())).isEqualTo(Format.JPEG);
        assertThat(photo.width()).isEqualTo(900);
    }

    @Test
    void exifGpsDateAndOrientationAreReadAndTheStoredFileHasNoMetadata() throws Exception {
        byte[] upload = TestImages.jpegWithExif(800, 600, 40.9903, 29.0290, "2026:09:01 14:30:00", 6);

        ProcessedPhoto photo = processor.process(upload);

        PhotoMetadata metadata = photo.metadata();
        assertThat(metadata.latitude()).isCloseTo(40.9903, within(1e-4));
        assertThat(metadata.longitude()).isCloseTo(29.0290, within(1e-4));
        assertThat(metadata.takenAt()).isEqualTo(LocalDateTime.of(2026, 9, 1, 14, 30).atZone(ISTANBUL).toInstant());
        assertThat(metadata.orientation()).isEqualTo(6);

        // Orientation 6 = rotate 90 degrees clockwise: 800 x 600 becomes 600 x 800
        assertThat(photo.width()).isEqualTo(600);
        assertThat(photo.height()).isEqualTo(800);
        // ...and the original top-left corner (red) is now top-right
        BufferedImage stored = ImageIO.read(new ByteArrayInputStream(photo.jpeg()));
        assertThat(isRed(stored.getRGB(stored.getWidth() - 20, 20))).isTrue();
        assertThat(isRed(stored.getRGB(20, 20))).isFalse();

        // Privacy: nothing of the original EXIF survives the re-encoding
        Metadata storedMetadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(photo.jpeg()));
        assertThat(storedMetadata.getFirstDirectoryOfType(GpsDirectory.class)).isNull();
        assertThat(storedMetadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class)).isNull();
    }

    @Test
    void southernAndWesternCoordinatesAreNegative() {
        PhotoMetadata metadata = processor.readMetadata(TestImages.jpegWithExif(800, 600, -33.8568, -70.6483, null, 1));

        assertThat(metadata.latitude()).isCloseTo(-33.8568, within(1e-4));
        assertThat(metadata.longitude()).isCloseTo(-70.6483, within(1e-4));
        assertThat(metadata.takenAt()).isNull();
    }

    @Test
    void photoWithoutExifHasNoProof() {
        assertThat(processor.process(TestImages.jpeg(800, 600)).metadata()).isEqualTo(PhotoMetadata.NONE);
    }

    @Test
    void everyOrientationGivesTheRightSize() {
        BufferedImage source = TestImages.picture(40, 20);
        for (int orientation = 1; orientation <= 8; orientation++) {
            BufferedImage oriented = PhotoImageProcessor.orient(source, orientation);
            boolean swapped = orientation >= 5;
            assertThat(oriented.getWidth()).as("orientation %d", orientation).isEqualTo(swapped ? 20 : 40);
            assertThat(oriented.getHeight()).as("orientation %d", orientation).isEqualTo(swapped ? 40 : 20);
        }
        // 3 = upside down: the red top-left corner moves to the bottom-right
        BufferedImage rotated = PhotoImageProcessor.orient(source, 3);
        assertThat(isRed(rotated.getRGB(38, 18))).isTrue();
        // 8 = rotate 90 degrees counter-clockwise: top-left moves to bottom-left
        BufferedImage left = PhotoImageProcessor.orient(source, 8);
        assertThat(isRed(left.getRGB(1, 38))).isTrue();
    }

    @Test
    void aiCopyIsAt512Pixels() throws Exception {
        ProcessedPhoto photo = processor.process(TestImages.jpeg(2000, 600));

        BufferedImage small = ImageIO.read(new ByteArrayInputStream(processor.downscaleForAi(photo.jpeg())));

        assertThat(Math.max(small.getWidth(), small.getHeight())).isEqualTo(512);
    }

    private static boolean isRed(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return r > 180 && g < 80 && b < 80;
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }
}
