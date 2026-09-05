package family.eilertsen.rack.adapter.out.filesystem;

import javax.imageio.IIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * Fitting a photograph to a size: the one piece of image arithmetic the app
 * does, shared by staging (a camera frame to 1568px) and thumbnails (a
 * photograph to 160 or 320).
 */
final class Jpegs {

    private Jpegs() {}

    /**
     * The image no longer than {@code maxEdge} on its longer side, as a JPEG,
     * turned the way the camera saw it. Null for a format ImageIO cannot read
     * — HEIC, WebP — so the caller can keep the bytes as they came.
     *
     * <p>With {@code keepMetadata}, what the camera wrote about the shot rides
     * along: the Exif block (when it was taken, which camera, where) and the
     * colour profile. The page used to make the photograph on a canvas,
     * which keeps none of it, so every photograph filed before this is a
     * picture and nothing else. The orientation tag is reset to "as is",
     * because the pixels have been turned and a viewer that honoured the old
     * tag would turn them again.
     *
     * <p>A camera frame is read subsampled when it is far larger than asked
     * for, so a 50-megapixel shot is never a 200MB bitmap on the server either:
     * the reader is asked to keep every nth pixel, chosen so that what it hands
     * back is still at least twice the target and the final scaling has
     * something to average. Then halved until it is under twice the target,
     * and scaled the rest of the way bilinearly, because one bilinear step from
     * four times the size drops three pixels in four.
     */
    static byte[] fit(byte[] original, int maxEdge, float quality, boolean keepMetadata) throws IOException {
        BufferedImage source = readNear(original, maxEdge);
        if (source == null) return null;
        BufferedImage scaled = scale(source, maxEdge);
        BufferedImage oriented = orient(scaled, Exif.orientation(original));
        byte[] jpeg = write(oriented, quality);
        return keepMetadata ? Exif.carryOver(original, jpeg) : jpeg;
    }

    private static BufferedImage readNear(byte[] original, int maxEdge) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(original))) {
            if (in == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                int longer = Math.max(reader.getWidth(0), reader.getHeight(0));
                int step = Math.max(1, longer / (2 * maxEdge));
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(step, step, 0, 0);
                return reader.read(0, param);
            } catch (IIOException unreadable) {
                // A CMYK JPEG, a truncated file: ImageIO knows the format and
                // still cannot produce pixels. Same answer as not knowing it.
                return null;
            } finally {
                reader.dispose();
            }
        }
    }

    private static BufferedImage scale(BufferedImage source, int maxEdge) {
        BufferedImage current = source;
        while (Math.max(current.getWidth(), current.getHeight()) > 2 * maxEdge) {
            current = draw(current, current.getWidth() / 2, current.getHeight() / 2);
        }
        double factor = Math.min(1.0, (double) maxEdge / Math.max(current.getWidth(), current.getHeight()));
        int width = Math.max(1, (int) Math.round(current.getWidth() * factor));
        int height = Math.max(1, (int) Math.round(current.getHeight() * factor));
        if (width == current.getWidth() && height == current.getHeight() && current.getType() == BufferedImage.TYPE_INT_RGB) {
            return current;
        }
        return draw(current, width, height);
    }

    /** RGB regardless of what came in: a JPEG writer has no alpha to keep. */
    private static BufferedImage draw(BufferedImage source, int width, int height) {
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    /**
     * The eight EXIF orientations. A phone stores the sensor's frame and a tag
     * saying which way up it was held; a browser turns the picture when it
     * decodes it, ImageIO does not, and a drawer photographed in portrait would
     * otherwise be filed on its side. Each case maps a source pixel (x, y) to
     * where it lands, as the six numbers of an affine transform.
     */
    static BufferedImage orient(BufferedImage image, int orientation) {
        if (orientation <= 1 || orientation > 8) return image;
        int w = image.getWidth(), h = image.getHeight();
        AffineTransform t = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);   // mirrored
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);  // upside down
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);   // flipped
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);    // transposed
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);   // turned 90° clockwise
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);  // transversed
            default -> new AffineTransform(0, -1, 1, 0, 0, w);  // 8: turned 90° anticlockwise
        };
        boolean turned = orientation >= 5;
        BufferedImage target = new BufferedImage(turned ? h : w, turned ? w : h, BufferedImage.TYPE_INT_RGB);
        new AffineTransformOp(t, AffineTransformOp.TYPE_NEAREST_NEIGHBOR).filter(image, target);
        return target;
    }

    private static byte[] write(BufferedImage image, float quality) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /**
     * Just enough of the JPEG container to find the metadata segments and
     * read one tag out of the Exif block: the APP1 segment, the TIFF header
     * inside it, and the first directory. Anything malformed or absent reads
     * as orientation 1 — the way up it already is — and carries nothing over.
     */
    static final class Exif {

        private Exif() {}

        private static final int ORIENTATION = 0x0112;

        static int orientation(byte[] jpeg) {
            try {
                Segment exif = exifSegment(jpeg);
                return exif == null ? 1 : orientationIn(exif.tiff(jpeg), true);
            } catch (RuntimeException malformed) {
                return 1;
            }
        }

        /**
         * The fitted JPEG with the original's Exif and ICC segments put back
         * in after its start marker, Exif first as the standard has it.
         *
         * <p>Only those two. Anything that describes the file's own bytes is
         * a lie about this file: MPF holds offsets into the one it came from,
         * and on a phone the XMP is a container directory — a Galaxy S24
         * writes one declaring an Ultra HDR gain map of 20,845 bytes appended
         * after the picture, a Pixel one declaring a motion-photo video. The
         * fitted file has no trailer, and Chrome on Android reads that XMP to
         * render HDR, went looking for the gain map, and froze the phone on
         * the first photograph that carried it.
         */
        static byte[] carryOver(byte[] original, byte[] fitted) {
            List<Segment> keep;
            try {
                keep = metadataSegments(original);
            } catch (RuntimeException malformed) {
                return fitted;
            }
            if (keep.isEmpty() || fitted.length < 2) return fitted;
            ByteArrayOutputStream out = new ByteArrayOutputStream(fitted.length + keep.stream().mapToInt(s -> s.length + 2).sum());
            out.write(fitted, 0, 2);
            for (Segment s : keep) {
                byte[] bytes = Arrays.copyOfRange(original, s.at, s.at + 2 + s.length);
                if (s.isExif()) resetOrientation(bytes);
                out.write(bytes, 0, bytes.length);
            }
            out.write(fitted, 2, fitted.length - 2);
            return out.toByteArray();
        }

        private record Segment(int marker, int at, int length, String signature) {
            boolean isExif() { return marker == 0xE1 && signature.startsWith("Exif"); }
            boolean isIcc() { return marker == 0xE2 && signature.startsWith("ICC_PROFILE"); }
            /** The TIFF structure that follows "Exif\0\0", as its own buffer with offsets from its start. */
            ByteBuffer tiff(byte[] jpeg) {
                return ByteBuffer.wrap(jpeg, at + 10, length - 8).slice();
            }
        }

        private static Segment exifSegment(byte[] jpeg) {
            for (Segment s : segments(jpeg)) if (s.isExif()) return s;
            return null;
        }

        private static List<Segment> metadataSegments(byte[] jpeg) {
            List<Segment> keep = new ArrayList<>();
            for (Segment s : segments(jpeg)) if (s.isExif() || s.isIcc()) keep.add(s);
            keep.sort((a, b) -> Boolean.compare(b.isExif(), a.isExif()));
            return keep;
        }

        /** The application segments between the start marker and the image data. */
        private static List<Segment> segments(byte[] b) {
            List<Segment> found = new ArrayList<>();
            if (b.length < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) return found;
            int at = 2;
            while (at + 4 <= b.length) {
                if ((b[at] & 0xFF) != 0xFF) return found;
                int marker = b[at + 1] & 0xFF;
                if (marker == 0xFF) { at++; continue; }
                if (marker == 0xD9 || marker == 0xDA) return found;            // image data: nothing more ahead
                if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8)) { at += 2; continue; }
                int length = ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
                if (length < 2 || at + 2 + length > b.length) return found;
                if (marker >= 0xE0 && marker <= 0xEF) {
                    found.add(new Segment(marker, at, length, new String(b, at + 4, Math.min(32, length - 2), java.nio.charset.StandardCharsets.ISO_8859_1)));
                }
                at += 2 + length;
            }
            return found;
        }

        /** Reads the tag, or with {@code reset} false writes 1 into it; returns what it read. */
        private static int orientationIn(ByteBuffer tiff, boolean readOnly) {
            tiff.order(tiff.getShort(0) == 0x4949 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
            int ifd = tiff.getInt(4);
            int entries = tiff.getShort(ifd) & 0xFFFF;
            for (int i = 0; i < entries; i++) {
                int entry = ifd + 2 + i * 12;
                if ((tiff.getShort(entry) & 0xFFFF) == ORIENTATION) {
                    int value = tiff.getShort(entry + 8) & 0xFFFF;
                    if (!readOnly) tiff.putShort(entry + 8, (short) 1);
                    return value >= 1 && value <= 8 ? value : 1;
                }
            }
            return 1;
        }

        /** {@code segment} is a copy of a whole APP1 Exif segment, marker first. */
        private static void resetOrientation(byte[] segment) {
            try {
                orientationIn(ByteBuffer.wrap(segment, 10, segment.length - 10).slice(), false);
            } catch (RuntimeException malformed) {
                // Left as it was: an unreadable block was not going to turn anything.
            }
        }
    }
}
