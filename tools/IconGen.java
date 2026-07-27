import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Generates the launcher icon PNGs. Run once from the repository root:
 *   java tools/IconGen.java
 * Output lands in app/src/main/res/mipmap-*dpi/ic_launcher.png
 */
public class IconGen {

    public static void main(String[] args) throws Exception {
        int[] sizes = {48, 72, 96, 144, 192};
        String[] dirs = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
        for (int i = 0; i < sizes.length; i++) {
            File out = new File("app/src/main/res/mipmap-" + dirs[i] + "/ic_launcher.png");
            out.getParentFile().mkdirs();
            ImageIO.write(render(sizes[i], true), "png", out);
        }
        // Foreground layer for adaptive icons (safe zone is the middle 2/3).
        File fg = new File("app/src/main/res/mipmap-xxxhdpi/ic_launcher_fg.png");
        ImageIO.write(render(432, false), "png", fg);
        System.out.println("icons written");
    }

    static BufferedImage render(int s, boolean withBackground) {
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        float u = s / 100f;      // one "unit" = 1% of the icon
        float cx = s * 0.5f, cy = s * 0.5f;
        // Adaptive foregrounds must stay inside the central safe zone.
        float scale = withBackground ? 1f : 0.62f;

        if (withBackground) {
            g.setPaint(new RadialGradientPaint(new Point2D.Float(cx, cy * 0.8f), s * 0.72f,
                    new float[]{0f, 1f},
                    new Color[]{new Color(0x24, 0x2A, 0x36), new Color(0x08, 0x09, 0x0C)}));
            g.fill(new RoundRectangle2D.Float(0, 0, s, s, 22 * u, 22 * u));

            // Brushed-metal streaks.
            g.setClip(new RoundRectangle2D.Float(0, 0, s, s, 22 * u, 22 * u));
            for (int i = 0; i < 90; i++) {
                float y = (float) (Math.random() * s);
                int a = 6 + (int) (Math.random() * 10);
                g.setColor(new Color(255, 255, 255, a));
                g.fill(new Rectangle2D.Float(0, y, s, 0.6f * u));
            }
            g.setClip(null);
        }

        // Outer reticle ring.
        Color amber = new Color(0xE8, 0xA3, 0x3D);
        g.setColor(new Color(amber.getRed(), amber.getGreen(), amber.getBlue(), 60));
        g.setStroke(new BasicStroke(2.4f * u * scale));
        float r = 33 * u * scale;
        g.draw(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));

        // Reticle ticks at the cardinal points.
        g.setColor(amber);
        g.setStroke(new BasicStroke(3f * u * scale, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
        for (int i = 0; i < 4; i++) {
            double a = Math.PI / 2 * i;
            float x0 = cx + (float) Math.cos(a) * r * 0.82f;
            float y0 = cy + (float) Math.sin(a) * r * 0.82f;
            float x1 = cx + (float) Math.cos(a) * r * 1.18f;
            float y1 = cy + (float) Math.sin(a) * r * 1.18f;
            g.draw(new Line2D.Float(x0, y0, x1, y1));
        }

        // Central chevron stack — the "arena" mark.
        g.setStroke(new BasicStroke(6.5f * u * scale, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < 2; i++) {
            float w = 17 * u * scale;
            float h = 11 * u * scale;
            float off = (i == 0 ? -7f : 7f) * u * scale;
            g.setColor(i == 0 ? amber : new Color(0xF6, 0xD3, 0x92));
            Path2D.Float p = new Path2D.Float();
            p.moveTo(cx - w, cy + off + h * 0.5f);
            p.lineTo(cx, cy + off - h * 0.5f);
            p.lineTo(cx + w, cy + off + h * 0.5f);
            g.draw(p);
        }

        g.dispose();
        return img;
    }
}
