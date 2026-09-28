package com.nomi.wayfinder.photo;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Test photos: plain JPEG / PNG files, optionally with a hand-built EXIF block (GPS, date, orientation). */
final class TestImages {

    private TestImages() {
    }

    static BufferedImage picture(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(40, 120, 200));
        g.fillRect(0, 0, width, height);
        // Top-left marker, to see where the image's top-left corner ends up after orientation
        g.setColor(Color.RED);
        g.fillRect(0, 0, width / 4, height / 4);
        g.dispose();
        return image;
    }

    static byte[] jpeg(int width, int height) {
        return write(picture(width, height), "jpeg");
    }

    static byte[] png(int width, int height) {
        return write(picture(width, height), "png");
    }

    static byte[] write(BufferedImage image, String format) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, format, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /**
     * A JPEG with an EXIF APP1 segment right after SOI.
     *
     * @param dateTimeOriginal "yyyy:MM:dd HH:mm:ss" or null
     */
    static byte[] jpegWithExif(int width, int height, Double latitude, Double longitude, String dateTimeOriginal,
                               int orientation) {
        byte[] jpeg = jpeg(width, height);
        byte[] app1 = exifSegment(latitude, longitude, dateTimeOriginal, orientation);
        byte[] result = new byte[jpeg.length + app1.length];
        System.arraycopy(jpeg, 0, result, 0, 2);
        System.arraycopy(app1, 0, result, 2, app1.length);
        System.arraycopy(jpeg, 2, result, 2 + app1.length, jpeg.length - 2);
        return result;
    }

    // Big-endian TIFF: IFD0 (Orientation, ExifIFD pointer, GPS pointer), Exif IFD (DateTimeOriginal), GPS IFD
    static byte[] exifSegment(Double latitude, Double longitude, String dateTimeOriginal, int orientation) {
        boolean gps = latitude != null && longitude != null;
        boolean date = dateTimeOriginal != null;
        ByteBuffer b = ByteBuffer.allocate(1024).order(ByteOrder.BIG_ENDIAN);
        b.put("MM".getBytes(StandardCharsets.US_ASCII)).putShort((short) 42).putInt(8);

        int ifd0Count = 1 + (date ? 1 : 0) + (gps ? 1 : 0);
        int exifOffset = 8 + 2 + ifd0Count * 12 + 4;
        int gpsOffset = exifOffset + (date ? 18 + 20 : 0);

        b.putShort((short) ifd0Count);
        entry(b, 0x0112, 3, 1, orientation << 16);
        if (date) {
            entry(b, 0x8769, 4, 1, exifOffset);
        }
        if (gps) {
            entry(b, 0x8825, 4, 1, gpsOffset);
        }
        b.putInt(0);

        if (date) {
            b.putShort((short) 1);
            entry(b, 0x9003, 2, 20, exifOffset + 18);
            b.putInt(0);
            b.put((dateTimeOriginal + "\0").getBytes(StandardCharsets.US_ASCII));
        }
        if (gps) {
            int data = gpsOffset + 2 + 4 * 12 + 4;
            b.putShort((short) 4);
            entry(b, 0x0001, 2, 2, (latitude >= 0 ? 'N' : 'S') << 24);
            entry(b, 0x0002, 5, 3, data);
            entry(b, 0x0003, 2, 2, (longitude >= 0 ? 'E' : 'W') << 24);
            entry(b, 0x0004, 5, 3, data + 24);
            b.putInt(0);
            rationals(b, Math.abs(latitude));
            rationals(b, Math.abs(longitude));
        }

        int tiffLength = b.position();
        ByteBuffer segment = ByteBuffer.allocate(4 + 6 + tiffLength).order(ByteOrder.BIG_ENDIAN);
        segment.put((byte) 0xFF).put((byte) 0xE1).putShort((short) (2 + 6 + tiffLength));
        segment.put("Exif\0\0".getBytes(StandardCharsets.US_ASCII));
        segment.put(b.array(), 0, tiffLength);
        return segment.array();
    }

    private static void entry(ByteBuffer b, int tag, int type, int count, int value) {
        b.putShort((short) tag).putShort((short) type).putInt(count).putInt(value);
    }

    // degrees / minutes / seconds as rationals
    private static void rationals(ByteBuffer b, double value) {
        int degrees = (int) value;
        double minutesFull = (value - degrees) * 60;
        int minutes = (int) minutesFull;
        long secondsThousandths = Math.round((minutesFull - minutes) * 60 * 1000);
        b.putInt(degrees).putInt(1);
        b.putInt(minutes).putInt(1);
        b.putInt((int) secondsThousandths).putInt(1000);
    }
}
