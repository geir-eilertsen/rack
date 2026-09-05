package family.eilertsen.rack.adapter.out.filesystem;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class JpegsTest {

    @Test
    void fitsTheLongerEdgeAndKeepsTheRatio() throws IOException {
        byte[] fitted = Jpegs.fit(jpeg(4000, 3000, Color.RED, Color.BLUE), 1568, 0.85f, false);
        BufferedImage image = read(fitted);
        assertThat(image.getWidth()).isEqualTo(1568);
        assertThat(image.getHeight()).isEqualTo(1176);
    }

    @Test
    void aSmallerPictureIsLeftItsSize() throws IOException {
        BufferedImage image = read(Jpegs.fit(jpeg(800, 600, Color.RED, Color.BLUE), 1568, 0.85f, false));
        assertThat(image.getWidth()).isEqualTo(800);
        assertThat(image.getHeight()).isEqualTo(600);
    }

    @Test
    void aFrameFarLargerThanAskedIsReadSubsampledAndStillFits() throws IOException {
        // 8000 wide against 264: read every 15th pixel, then scaled the rest of the way.
        BufferedImage image = read(Jpegs.fit(jpeg(8000, 6000, Color.RED, Color.BLUE), 264, 0.8f, false));
        assertThat(image.getWidth()).isEqualTo(264);
        assertThat(image.getHeight()).isEqualTo(198);
        assertThat(near(image.getRGB(10, 100), Color.RED)).isTrue();
        assertThat(near(image.getRGB(250, 100), Color.BLUE)).isTrue();
    }

    @Test
    void turnsThePictureTheWayTheCameraSawIt() throws IOException {
        // Orientation 6: the sensor frame is turned 90° clockwise to view. A
        // landscape frame with red on the left becomes portrait with red on top.
        byte[] tagged = withOrientation(jpeg(400, 200, Color.RED, Color.BLUE), 6);
        BufferedImage image = read(Jpegs.fit(tagged, 1568, 0.85f, true));
        assertThat(image.getWidth()).isEqualTo(200);
        assertThat(image.getHeight()).isEqualTo(400);
        assertThat(near(image.getRGB(100, 20), Color.RED)).isTrue();
        assertThat(near(image.getRGB(100, 380), Color.BLUE)).isTrue();
        // And the tag now says "as is", so nothing turns it a second time.
        assertThat(Jpegs.Exif.orientation(Jpegs.fit(tagged, 1568, 0.85f, true))).isEqualTo(1);
    }

    @Test
    void anticlockwiseTooAndTheFlips() throws IOException {
        byte[] frame = jpeg(400, 200, Color.RED, Color.BLUE);
        BufferedImage eight = read(Jpegs.fit(withOrientation(frame, 8), 1568, 0.85f, false));
        assertThat(eight.getWidth()).isEqualTo(200);
        assertThat(near(eight.getRGB(100, 380), Color.RED)).isTrue();   // red went to the bottom
        BufferedImage two = read(Jpegs.fit(withOrientation(frame, 2), 1568, 0.85f, false));
        assertThat(two.getWidth()).isEqualTo(400);
        assertThat(near(two.getRGB(390, 100), Color.RED)).isTrue();     // mirrored: red on the right
        BufferedImage three = read(Jpegs.fit(withOrientation(frame, 3), 1568, 0.85f, false));
        assertThat(near(three.getRGB(390, 100), Color.RED)).isTrue();
    }

    @Test
    void whatTheCameraWroteAboutTheShotRidesAlong() throws IOException {
        byte[] tagged = withOrientation(jpeg(400, 200, Color.RED, Color.BLUE), 6);
        byte[] kept = Jpegs.fit(tagged, 1568, 0.85f, true);
        byte[] bare = Jpegs.fit(tagged, 1568, 0.85f, false);
        assertThat(new String(kept, StandardCharsets.ISO_8859_1)).contains("Exif");
        assertThat(new String(bare, StandardCharsets.ISO_8859_1)).doesNotContain("Exif");
        // Still a JPEG a reader opens, with the segment where the standard puts it.
        assertThat(read(kept).getWidth()).isEqualTo(200);
        assertThat(kept[2] & 0xFF).isEqualTo(0xFF);
        assertThat(kept[3] & 0xFF).isEqualTo(0xE1);
    }

    @Test
    void somethingThatIsNotAPictureIsNull() throws IOException {
        assertThat(Jpegs.fit("a frame".getBytes(StandardCharsets.UTF_8), 1568, 0.85f, true)).isNull();
    }

    @Test
    void anUnreadableTagReadsAsTheWayUpItIs() {
        assertThat(Jpegs.Exif.orientation(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0, 3})).isEqualTo(1);
        assertThat(Jpegs.Exif.orientation(new byte[0])).isEqualTo(1);
    }

    // -- helpers --

    static byte[] jpeg(int width, int height, Color left, Color right) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(left);
        g.fillRect(0, 0, width / 2, height);
        g.setColor(right);
        g.fillRect(width / 2, 0, width - width / 2, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", out);
        return out.toByteArray();
    }

    /** Splices a minimal Exif APP1 segment carrying one tag in after the start marker. */
    static byte[] withOrientation(byte[] jpeg, int orientation) {
        ByteBuffer tiff = ByteBuffer.allocate(8 + 2 + 12 + 4);
        tiff.put((byte) 'M').put((byte) 'M').putShort((short) 42).putInt(8);
        tiff.putShort((short) 1);
        tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        tiff.putInt(0);
        byte[] body = tiff.array();
        ByteBuffer segment = ByteBuffer.allocate(2 + 2 + 6 + body.length);
        segment.put((byte) 0xFF).put((byte) 0xE1).putShort((short) (2 + 6 + body.length));
        segment.put("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1)).put(body);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(segment.array(), 0, segment.array().length);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    static BufferedImage read(byte[] bytes) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    static boolean near(int rgb, Color c) {
        Color got = new Color(rgb);
        return Math.abs(got.getRed() - c.getRed()) < 40 && Math.abs(got.getGreen() - c.getGreen()) < 40
            && Math.abs(got.getBlue() - c.getBlue()) < 40;
    }
}
