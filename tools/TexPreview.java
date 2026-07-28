import com.arena3.game.Tex;
import com.arena3.render.ProcTex;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Dumps every generated texture into one contact sheet for eyeballing. */
public class TexPreview {
    public static void main(String[] args) throws Exception {
        String[] names = {"WALL_TECH", "WALL_PANEL", "WALL_RUST", "FLOOR_METAL", "FLOOR_GRATE",
                "CONCRETE", "TRIM", "TRIM_LIGHT", "LAVA", "ROCK", "SKY", "TELEPAD",
                "JUMPPAD", "METAL_DARK", "HAZARD", "CEILING"};
        int cols = 4, cell = 200, pad = 26;
        int w = cols * (cell + pad) + pad;
        int rows = (Tex.COUNT + cols - 1) / cols;
        int h = rows * (cell + pad + 16) + pad;
        BufferedImage sheet = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x11141A));
        g.fillRect(0, 0, w, h);

        for (int i = 0; i < Tex.COUNT; i++) {
            int[] px = ProcTex.generate(i);
            BufferedImage img = new BufferedImage(ProcTex.SIZE, ProcTex.SIZE, BufferedImage.TYPE_INT_ARGB);
            img.setRGB(0, 0, ProcTex.SIZE, ProcTex.SIZE, px, 0, ProcTex.SIZE);
            int cx = pad + (i % cols) * (cell + pad);
            int cy = pad + (i / cols) * (cell + pad + 16);
            g.drawImage(img, cx, cy, cell, cell, null);
            g.setColor(new Color(0x2C3340));
            g.drawRect(cx, cy, cell, cell);
            g.setColor(new Color(0xD8DEE9));
            g.setFont(new Font("SansSerif", Font.PLAIN, 11));
            g.drawString(names[i], cx, cy + cell + 14);
        }
        g.dispose();
        File out = new File(args.length > 0 ? args[0] : "textures.png");
        ImageIO.write(sheet, "png", out);
        System.out.println("wrote " + out.getAbsolutePath());
    }
}
