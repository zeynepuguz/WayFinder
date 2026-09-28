package com.nomi.wayfinder.photo;

import com.drew.imaging.ImageMetadataReader;
import com.drew.lang.GeoLocation;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Iterator;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.Semaphore;

/**
 * Turns an uploaded file into the JPEGs we store:
 * - the type is sniffed from the bytes (JPEG, PNG, WebP), never taken from the file name or Content-Type
 * - EXIF GPS, DateTimeOriginal and Orientation are read BEFORE re-encoding (the location proof)
 * - the image is turned upright, scaled to at most 1600 px (+ a 480 px thumbnail) and re-encoded;
 *   the new files carry no metadata at all (no GPS, no camera, no date), which protects the uploader
 */
@Component
public class PhotoImageProcessor {

    static final int MIN_SHORT_SIDE = 600;
    static final int MAX_LONG_SIDE = 1600;
    static final int THUMB_LONG_SIDE = 480;
    static final int AI_LONG_SIDE = 512;
    // Decompression bomb guard: a 12 MB PNG can claim 20000 x 20000 pixels
    static final long MAX_PIXELS = 100_000_000L;
    static final float QUALITY = 0.85f;
    static final float THUMB_QUALITY = 0.8f;

    private static final Set<String> HEIF_BRANDS = Set.of(
            "heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", "mif1", "msf1", "avif", "avis");

    // Decoding a 12 MP photo needs ~50 MB; a few at a time is plenty
    private final Semaphore permits = new Semaphore(3);
    private final TimeZone zone;

    @Autowired
    public PhotoImageProcessor(com.nomi.wayfinder.config.NomiProperties properties) {
        this(TimeZone.getTimeZone(properties.timezone()));
    }

    PhotoImageProcessor(TimeZone zone) {
        this.zone = zone;
        // Registers the WebP reader (TwelveMonkeys) also inside Spring Boot's nested jar class loader
        ImageIO.scanForPlugins();
    }

    public enum Format {
        JPEG, PNG, WEBP, HEIF, UNKNOWN
    }

    /**
     * @param latitude  EXIF GPS, null when the photo has none
     * @param takenAt   EXIF DateTimeOriginal (camera clock, read in the app's time zone), null when missing
     * @param orientation EXIF orientation 1..8 (1 = upright)
     */
    public record PhotoMetadata(Double latitude, Double longitude, Instant takenAt, int orientation) {

        public static final PhotoMetadata NONE = new PhotoMetadata(null, null, null, 1);

        public boolean hasGps() {
            return latitude != null && longitude != null;
        }
    }

    public record ProcessedPhoto(byte[] jpeg, int width, int height, byte[] thumbnail, PhotoMetadata metadata) {
    }

    public ProcessedPhoto process(byte[] bytes) {
        Format format = sniff(bytes);
        if (format != Format.JPEG && format != Format.PNG && format != Format.WEBP) {
            throw new PhotoRejectedException(RejectReason.UNSUPPORTED);
        }
        PhotoMetadata metadata = readMetadata(bytes);

        permits.acquireUninterruptibly();
        try {
            BufferedImage image = decode(bytes, MAX_LONG_SIDE, MIN_SHORT_SIDE);
            BufferedImage large = orient(scale(image, MAX_LONG_SIDE), metadata.orientation());
            BufferedImage thumb = scale(large, THUMB_LONG_SIDE);
            return new ProcessedPhoto(encode(large, QUALITY), large.getWidth(), large.getHeight(),
                    encode(thumb, THUMB_QUALITY), metadata);
        } finally {
            permits.release();
        }
    }

    // The small copy sent to the AI service (low detail is enough and costs less)
    public byte[] downscaleForAi(byte[] storedJpeg) {
        permits.acquireUninterruptibly();
        try {
            return encode(scale(decode(storedJpeg, AI_LONG_SIDE, 1), AI_LONG_SIDE), THUMB_QUALITY);
        } finally {
            permits.release();
        }
    }

    public static Format sniff(byte[] b) {
        if (b == null || b.length < 12) {
            return Format.UNKNOWN;
        }
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return Format.JPEG;
        }
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return Format.PNG;
        }
        if (ascii(b, 0, 4).equals("RIFF") && ascii(b, 8, 4).equals("WEBP")) {
            return Format.WEBP;
        }
        // ISO base media file: [size]["ftyp"][brand]; iPhones save HEIC by default
        if (ascii(b, 4, 4).equals("ftyp") && HEIF_BRANDS.contains(ascii(b, 8, 4).toLowerCase(java.util.Locale.ROOT))) {
            return Format.HEIF;
        }
        return Format.UNKNOWN;
    }

    private static String ascii(byte[] b, int offset, int length) {
        return new String(b, offset, length, StandardCharsets.ISO_8859_1);
    }

    PhotoMetadata readMetadata(byte[] bytes) {
        Metadata metadata;
        try {
            metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes), bytes.length);
        } catch (Exception e) {
            // Broken / missing metadata is not an error: the photo just has no EXIF proof
            return PhotoMetadata.NONE;
        }

        Double latitude = null;
        Double longitude = null;
        GpsDirectory gps = metadata.getFirstDirectoryOfType(GpsDirectory.class);
        GeoLocation location = gps == null ? null : gps.getGeoLocation();
        if (location != null && !location.isZero() && validCoordinates(location.getLatitude(), location.getLongitude())) {
            latitude = location.getLatitude();
            longitude = location.getLongitude();
        }

        Instant takenAt = null;
        ExifSubIFDDirectory exif = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
        if (exif != null) {
            Date date = exif.getDateOriginal(zone);
            takenAt = date == null ? null : date.toInstant();
        }

        int orientation = 1;
        ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
        if (ifd0 != null && ifd0.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
            Integer value = ifd0.getInteger(ExifIFD0Directory.TAG_ORIENTATION);
            if (value != null && value >= 1 && value <= 8) {
                orientation = value;
            }
        }
        return new PhotoMetadata(latitude, longitude, takenAt, orientation);
    }

    static boolean validCoordinates(double latitude, double longitude) {
        return Double.isFinite(latitude) && Double.isFinite(longitude)
                && latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180;
    }

    /**
     * Decodes at a reduced resolution when the file is much larger than needed (subsampling keeps the long
     * side at least {@code targetLongSide}), so big photos never need their full size in memory.
     */
    private static BufferedImage decode(byte[] bytes, int targetLongSide, int minShortSide) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) {
                throw new PhotoRejectedException(RejectReason.UNSUPPORTED);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if ((long) width * height > MAX_PIXELS) {
                    throw new PhotoRejectedException(RejectReason.UNSUPPORTED);
                }
                if (Math.min(width, height) < minShortSide) {
                    throw new PhotoRejectedException(RejectReason.TOO_SMALL);
                }
                ImageReadParam param = reader.getDefaultReadParam();
                int factor = Math.max(1, Math.max(width, height) / targetLongSide);
                if (factor > 1) {
                    param.setSourceSubsampling(factor, factor, 0, 0);
                }
                BufferedImage image = reader.read(0, param);
                if (image == null) {
                    throw new PhotoRejectedException(RejectReason.UNSUPPORTED);
                }
                return image;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof PhotoRejectedException rejected) {
                throw rejected;
            }
            // Truncated file, CMYK JPEG, unknown WebP variant...
            throw new PhotoRejectedException(RejectReason.UNSUPPORTED);
        }
    }

    // Opaque RGB (transparent PNG areas become white), scaled so the long side is at most maxLongSide
    static BufferedImage scale(BufferedImage source, int maxLongSide) {
        int width = source.getWidth();
        int height = source.getHeight();
        double ratio = Math.min(1.0, (double) maxLongSide / Math.max(width, height));
        int targetWidth = Math.max(1, (int) Math.round(width * ratio));
        int targetHeight = Math.max(1, (int) Math.round(height * ratio));

        BufferedImage current = source;
        // Halving steps first: one big bilinear step would look jagged
        while (current.getWidth() / 2 >= targetWidth && current.getHeight() / 2 >= targetHeight) {
            current = draw(current, current.getWidth() / 2, current.getHeight() / 2);
        }
        if (current.getWidth() != targetWidth || current.getHeight() != targetHeight
                || current.getType() != BufferedImage.TYPE_INT_RGB) {
            current = draw(current, targetWidth, targetHeight);
        }
        return current;
    }

    private static BufferedImage draw(BufferedImage source, int width, int height) {
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    // Applies the EXIF orientation, so the stored photo (which has no EXIF any more) is upright
    static BufferedImage orient(BufferedImage source, int orientation) {
        if (orientation <= 1 || orientation > 8) {
            return source;
        }
        int w = source.getWidth();
        int h = source.getHeight();
        AffineTransform t = new AffineTransform();
        switch (orientation) {
            case 2 -> { t.scale(-1.0, 1.0); t.translate(-w, 0); }
            case 3 -> { t.translate(w, h); t.rotate(Math.PI); }
            case 4 -> { t.scale(1.0, -1.0); t.translate(0, -h); }
            case 5 -> { t.rotate(-Math.PI / 2); t.scale(-1.0, 1.0); }
            case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); }
            case 7 -> { t.scale(-1.0, 1.0); t.translate(-h, 0); t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            default -> { t.translate(0, w); t.rotate(3 * Math.PI / 2); }
        }
        boolean swap = orientation >= 5;
        BufferedImage target = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.drawImage(source, t, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    // A fresh JPEG: ImageIO writes only a JFIF header, none of the original metadata
    static byte[] encode(BufferedImage image, float quality) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024);
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
