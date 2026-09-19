package com.smali_generator.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * The contract {@link Icons} relies on, checked on the art itself.
 *
 * Nothing here can exercise Icons: BitmapFactory and AssetManager are
 * unimplemented stubs off a device. What it can do is assert the properties
 * the loader assumes, each of which fails in a way that is hard to see on a
 * phone -- a tinted opaque background is a green block where an icon should
 * be, and art that arrived in colour comes out a flat silhouette.
 *
 * It is also the only check that these files are where Icons will look for
 * them. That is the price of assets over a compiled-in constant: the name is a
 * string resolved at runtime, so the compiler cannot notice a moved file.
 */
public class IconsTest {

    /** Where the assets live, relative to the module. */
    private static final String ASSETS = "src/main/assets/";

    /**
     * The smallest source that still decodes sharp.
     *
     * Icons decodes to 48dp, which is 192px on an xxxhdpi screen; art below
     * that is upscaled and soft on the densest phones.
     */
    private static final int MIN_SIZE = 192;

    /** Ink, for measuring. Below this a pixel is the antialiased edge. */
    private static final int INK_ALPHA = 128;

    /** How far off centre the art may sit, as a fraction of its width. */
    private static final double CENTRING_TOLERANCE = 0.02;

    @Test
    public void the_plaster_is_a_tintable_mask() throws IOException {
        assertIsATintableMask(Icons.PATCH);
    }

    @Test
    public void the_bar_chart_is_a_tintable_mask() throws IOException {
        assertIsATintableMask(Icons.GRAPH);
    }

    private void assertIsATintableMask(String asset) throws IOException {
        Png png = Png.read(locate(asset), asset);

        assertEquals(asset + ": not square, so fitting it leaves a lopsided gap",
                png.width, png.height);
        assertTrue(asset + ": " + png.width + "px is too small to decode sharp",
                png.width >= MIN_SIZE);

        int left = png.width;
        int top = png.height;
        int right = -1;
        int bottom = -1;
        for (int y = 0; y < png.height; y++) {
            for (int x = 0; x < png.width; x++) {
                if (png.alpha(x, y) <= INK_ALPHA) {
                    continue;
                }
                // SRC_IN keeps the alpha and throws the colour away, so art
                // that carries colour loses it silently.
                assertTrue(asset + ": ink at " + x + "," + y + " is coloured, "
                                + "so tinting would flatten it",
                        png.red(x, y) == png.green(x, y) && png.green(x, y) == png.blue(x, y));
                left = Math.min(left, x);
                right = Math.max(right, x);
                top = Math.min(top, y);
                bottom = Math.max(bottom, y);
            }
        }
        assertTrue(asset + ": no ink in it at all", right >= left);

        // An opaque background would be tinted along with the art, filling the
        // icon's whole column with a flat square.
        assertTransparent(asset, png, 0, 0);
        assertTransparent(asset, png, png.width - 1, 0);
        assertTransparent(asset, png, 0, png.height - 1);
        assertTransparent(asset, png, png.width - 1, png.height - 1);

        // FIT_CENTER centres the bitmap, not the ink inside it, so art that
        // sits off-centre in its own square sits off-centre in the row.
        double slack = png.width * CENTRING_TOLERANCE;
        assertTrue(asset + ": ink is off centre horizontally, insets "
                        + left + " and " + (png.width - 1 - right),
                Math.abs(left - (png.width - 1 - right)) <= slack);
        assertTrue(asset + ": ink is off centre vertically, insets "
                        + top + " and " + (png.height - 1 - bottom),
                Math.abs(top - (png.height - 1 - bottom)) <= slack);
    }

    private void assertTransparent(String asset, Png png, int x, int y) {
        assertEquals(asset + ": corner " + x + "," + y + " is not transparent",
                0, png.alpha(x, y));
    }

    /**
     * As much of PNG as these two files need: 8-bit RGBA, non-interlaced.
     *
     * Written out rather than delegated to because an Android unit test
     * compiles against android.jar, where javax.imageio does not exist. The
     * narrowness is deliberate -- anything this cannot read is something Icons
     * was not written for either, so a file outside that shape should fail the
     * test rather than be decoded by a more forgiving reader.
     */
    private static final class Png {
        final int width;
        final int height;

        /** RGBA, row-major, already unfiltered. */
        private final byte[] pixels;

        private Png(int width, int height, byte[] pixels) {
            this.width = width;
            this.height = height;
            this.pixels = pixels;
        }

        int red(int x, int y) {
            return pixels[(y * width + x) * 4] & 0xFF;
        }

        int green(int x, int y) {
            return pixels[(y * width + x) * 4 + 1] & 0xFF;
        }

        int blue(int x, int y) {
            return pixels[(y * width + x) * 4 + 2] & 0xFF;
        }

        int alpha(int x, int y) {
            return pixels[(y * width + x) * 4 + 3] & 0xFF;
        }

        static Png read(File file, String asset) throws IOException {
            byte[] data = Files.readAllBytes(file.toPath());
            assertTrue(asset + ": not a PNG", data.length > 8 && (data[0] & 0xFF) == 0x89
                    && data[1] == 'P' && data[2] == 'N' && data[3] == 'G');
            int width = 0;
            int height = 0;
            int depth = 0;
            int colourType = -1;
            int interlace = -1;
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            int at = 8;
            while (at + 8 <= data.length) {
                int length = intAt(data, at);
                String type = new String(data, at + 4, 4, StandardCharsets.US_ASCII);
                int body = at + 8;
                if ("IHDR".equals(type)) {
                    width = intAt(data, body);
                    height = intAt(data, body + 4);
                    depth = data[body + 8] & 0xFF;
                    colourType = data[body + 9] & 0xFF;
                    interlace = data[body + 12] & 0xFF;
                } else if ("IDAT".equals(type)) {
                    compressed.write(data, body, length);
                } else if ("IEND".equals(type)) {
                    break;
                }
                at = body + length + 4;
            }
            assertEquals(asset + ": not 8 bits per channel", 8, depth);
            assertEquals(asset + ": not RGBA (colour type 6)", 6, colourType);
            assertEquals(asset + ": interlaced", 0, interlace);
            return new Png(width, height, unfilter(inflate(compressed.toByteArray()), width, height));
        }

        private static int intAt(byte[] data, int at) {
            return ((data[at] & 0xFF) << 24) | ((data[at + 1] & 0xFF) << 16)
                    | ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
        }

        private static byte[] inflate(byte[] input) throws IOException {
            Inflater inflater = new Inflater();
            inflater.setInput(input);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1 << 15];
            try {
                while (!inflater.finished()) {
                    int produced = inflater.inflate(buffer);
                    if (produced == 0) {
                        break;
                    }
                    out.write(buffer, 0, produced);
                }
            } catch (DataFormatException broken) {
                throw new IOException(broken);
            } finally {
                inflater.end();
            }
            return out.toByteArray();
        }

        /** Each scanline carries a filter byte; undo it against the neighbours. */
        private static byte[] unfilter(byte[] raw, int width, int height) {
            int stride = width * 4;
            byte[] out = new byte[stride * height];
            int at = 0;
            for (int y = 0; y < height; y++) {
                int filter = raw[at++] & 0xFF;
                int row = y * stride;
                int above = row - stride;
                for (int i = 0; i < stride; i++) {
                    int value = raw[at + i] & 0xFF;
                    int left = i >= 4 ? out[row + i - 4] & 0xFF : 0;
                    int up = y > 0 ? out[above + i] & 0xFF : 0;
                    int upLeft = i >= 4 && y > 0 ? out[above + i - 4] & 0xFF : 0;
                    switch (filter) {
                        case 0:
                            break;
                        case 1:
                            value += left;
                            break;
                        case 2:
                            value += up;
                            break;
                        case 3:
                            value += (left + up) >> 1;
                            break;
                        case 4:
                            value += paeth(left, up, upLeft);
                            break;
                        default:
                            throw new IllegalStateException("unknown PNG filter " + filter);
                    }
                    out[row + i] = (byte) value;
                }
                at += stride;
            }
            return out;
        }

        private static int paeth(int left, int up, int upLeft) {
            int estimate = left + up - upLeft;
            int toLeft = Math.abs(estimate - left);
            int toUp = Math.abs(estimate - up);
            int toUpLeft = Math.abs(estimate - upLeft);
            if (toLeft <= toUp && toLeft <= toUpLeft) {
                return left;
            }
            return toUp <= toUpLeft ? up : upLeft;
        }
    }

    /** Gradle runs a test from the module directory; a run from elsewhere says so. */
    private static File locate(String asset) {
        String[] candidates = {ASSETS, "app/" + ASSETS, "smali_generator/app/" + ASSETS};
        for (String candidate : candidates) {
            File at = new File(candidate + asset);
            if (at.isFile()) {
                return at;
            }
        }
        fail("cannot find " + asset + " from " + new File(".").getAbsolutePath());
        return null;
    }
}
