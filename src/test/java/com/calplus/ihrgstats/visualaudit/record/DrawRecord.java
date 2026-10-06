package com.calplus.ihrgstats.visualaudit.record;

import java.awt.Font;
import java.awt.Rectangle;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything one report image drew, in the coordinates of the PNG that was
 * finally written (a cropped table image is translated by its crop origin).
 * Produced by {@link DrawRecorder}; consumed by {@link ImageChecks}.
 *
 * The model is deliberately mutable and deep-copyable so the calibration
 * suite can plant defects in a COPY of a clean record and prove the checks
 * catch them.
 */
public final class DrawRecord {

    public String name = "";          // variant name, set by the harness
    public String generator = "";     // table | info | comparison
    public String file = "";          // PNG file name as written by the product
    public long fileBytes;            // size of that PNG on disk
    public int canvasWidth;           // the canvas the generator drew on (before any crop)
    public int canvasHeight;
    public int originX;               // crop origin of the written image inside the canvas
    public int originY;
    public int width;                 // written PNG size
    public int height;
    public final List<Text> texts = new ArrayList<>();
    public final List<Icon> icons = new ArrayList<>();
    public final List<Rect> rects = new ArrayList<>();
    /** Draw calls the model does not interpret (lines, shapes, glyph vectors ...), kept so nothing drawn is silently ignored. */
    public final List<String> otherCalls = new ArrayList<>();

    /** One drawString call. */
    public static final class Text {
        public int seq;
        public String s;
        public float x;               // baseline origin
        public float y;
        public transient Font font;   // the exact Font object used (JSON keeps its description)
        public transient FontRenderContext frc;
        public String color;
        public boolean transformed;   // drawn under a non-translation transform (watermark-like)
        public double advance;        // measured logical advance in px (real font advances)
        public int intAdvance;        // FontMetrics.stringWidth - what the generators lay out with
        public float ascent;
        public float descent;
        public Rectangle2D logical;   // [x, y-ascent, advance, ascent+descent]
        public Rectangle ink;         // pixel bounds of the rendered glyphs (null for blank text)
        public int canDisplayUpTo;    // Font.canDisplayUpTo(s): -1 = every char has a glyph

        public Text copy() {
            Text t = new Text();
            t.seq = seq; t.s = s; t.x = x; t.y = y; t.font = font; t.frc = frc; t.color = color;
            t.transformed = transformed; t.advance = advance; t.intAdvance = intAdvance;
            t.ascent = ascent; t.descent = descent;
            t.logical = logical == null ? null : (Rectangle2D) logical.clone();
            t.ink = ink == null ? null : new Rectangle(ink);
            t.canDisplayUpTo = canDisplayUpTo;
            return t;
        }

        /** Recomputes every measured field from (s, x, y, font, frc) - used after a planted change. */
        public void remeasure() {
            Rectangle2D sb = font.getStringBounds(s, frc);
            advance = sb.getWidth();
            intAdvance = (int) Math.round(advance);
            java.awt.font.LineMetrics lm = font.getLineMetrics(s.isEmpty() ? " " : s, frc);
            ascent = lm.getAscent();
            descent = lm.getDescent();
            logical = new Rectangle2D.Double(x, y - ascent, advance, ascent + descent);
            ink = null;
            if (!s.isEmpty()) {
                TextLayout layout = new TextLayout(s, font, frc);
                Rectangle pb = layout.getPixelBounds(frc, x, y);
                ink = pb.isEmpty() ? null : pb;
            }
            canDisplayUpTo = font.canDisplayUpTo(s);
        }

        public void translate(int dx, int dy) {
            x += dx; y += dy;
            logical.setRect(logical.getX() + dx, logical.getY() + dy, logical.getWidth(), logical.getHeight());
            if (ink != null) ink.translate(dx, dy);
        }

        public String fontDescription() {
            return font == null ? "?" : font.getFontName() + " " + styleName(font.getStyle()) + " " + font.getSize2D();
        }
    }

    /** One drawImage call. */
    public static final class Icon {
        public int seq;
        public Rectangle dest;        // destination bounds (device space)
        public int drawW;             // requested destination size (user space, before any rotation)
        public int drawH;
        public int srcW;              // size of the image object actually drawn
        public int srcH;
        public String source;         // resource it was derived from, when recognised (e.g. halls/tanjong.png)
        public int sourceW;           // that resource's own size (== srcW/srcH when unrecognised)
        public int sourceH;
        public Rectangle opaque;      // destination bounds of the visibly opaque pixels (alpha >= 64)
        public boolean transformed;   // drawn under a non-translation transform (the tiled watermark)
        public boolean translucent;   // source pixels are all faint (alpha < 128): watermark tile

        public Icon copy() {
            Icon i = new Icon();
            i.seq = seq; i.dest = new Rectangle(dest); i.drawW = drawW; i.drawH = drawH; i.srcW = srcW; i.srcH = srcH; i.source = source;
            i.sourceW = sourceW; i.sourceH = sourceH; i.opaque = opaque == null ? null : new Rectangle(opaque);
            i.transformed = transformed; i.translucent = translucent;
            return i;
        }

        public void translate(int dx, int dy) {
            dest.translate(dx, dy);
            if (opaque != null) opaque.translate(dx, dy);
        }
    }

    /** One fillRect / fill(Shape) call. */
    public static final class Rect {
        public int seq;
        public Rectangle bounds;
        public String color;
        public boolean transformed;

        public Rect copy() {
            Rect r = new Rect();
            r.seq = seq; r.bounds = new Rectangle(bounds); r.color = color; r.transformed = transformed;
            return r;
        }
    }

    public DrawRecord copy() {
        DrawRecord r = new DrawRecord();
        r.name = name; r.generator = generator; r.file = file; r.fileBytes = fileBytes;
        r.canvasWidth = canvasWidth; r.canvasHeight = canvasHeight; r.originX = originX; r.originY = originY;
        r.width = width; r.height = height;
        texts.forEach(t -> r.texts.add(t.copy()));
        icons.forEach(i -> r.icons.add(i.copy()));
        rects.forEach(x -> r.rects.add(x.copy()));
        r.otherCalls.addAll(otherCalls);
        return r;
    }

    static String styleName(int style) {
        return switch (style) {
            case Font.BOLD -> "bold";
            case Font.ITALIC -> "italic";
            case Font.BOLD | Font.ITALIC -> "bolditalic";
            default -> "plain";
        };
    }

    // --- JSON (hand-written: no JSON library on the test classpath) ---------

    public String toJson() {
        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("{\n");
        kv(sb, "name", name).append(",\n");
        kv(sb, "generator", generator).append(",\n");
        kv(sb, "file", file).append(",\n");
        sb.append("  \"fileBytes\": ").append(fileBytes).append(",\n");
        sb.append("  \"canvas\": [").append(canvasWidth).append(", ").append(canvasHeight).append("],\n");
        sb.append("  \"origin\": [").append(originX).append(", ").append(originY).append("],\n");
        sb.append("  \"size\": [").append(width).append(", ").append(height).append("],\n");
        sb.append("  \"texts\": [\n");
        for (int i = 0; i < texts.size(); i++) {
            Text t = texts.get(i);
            sb.append("    {\"seq\": ").append(t.seq)
              .append(", \"s\": ").append(q(t.s))
              .append(", \"x\": ").append(num(t.x)).append(", \"y\": ").append(num(t.y))
              .append(", \"font\": ").append(q(t.fontDescription()))
              .append(", \"color\": ").append(q(t.color))
              .append(", \"advance\": ").append(num(t.advance))
              .append(", \"intAdvance\": ").append(t.intAdvance)
              .append(", \"logical\": ").append(rect(t.logical))
              .append(", \"ink\": ").append(rect(t.ink))
              .append(", \"canDisplayUpTo\": ").append(t.canDisplayUpTo)
              .append(t.transformed ? ", \"transformed\": true" : "")
              .append("}").append(i < texts.size() - 1 ? ",\n" : "\n");
        }
        sb.append("  ],\n  \"icons\": [\n");
        for (int i = 0; i < icons.size(); i++) {
            Icon c = icons.get(i);
            sb.append("    {\"seq\": ").append(c.seq)
              .append(", \"dest\": ").append(rect(c.dest))
              .append(", \"drawSize\": [").append(c.drawW).append(", ").append(c.drawH).append("]")
              .append(", \"drawnImage\": [").append(c.srcW).append(", ").append(c.srcH).append("]")
              .append(", \"source\": ").append(q(c.source))
              .append(", \"sourceSize\": [").append(c.sourceW).append(", ").append(c.sourceH).append("]")
              .append(", \"opaque\": ").append(rect(c.opaque))
              .append(c.transformed ? ", \"transformed\": true" : "")
              .append(c.translucent ? ", \"translucent\": true" : "")
              .append("}").append(i < icons.size() - 1 ? ",\n" : "\n");
        }
        sb.append("  ],\n  \"rects\": [\n");
        for (int i = 0; i < rects.size(); i++) {
            Rect r = rects.get(i);
            sb.append("    {\"seq\": ").append(r.seq)
              .append(", \"bounds\": ").append(rect(r.bounds))
              .append(", \"color\": ").append(q(r.color))
              .append(r.transformed ? ", \"transformed\": true" : "")
              .append("}").append(i < rects.size() - 1 ? ",\n" : "\n");
        }
        sb.append("  ],\n  \"otherCalls\": [");
        for (int i = 0; i < otherCalls.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append(q(otherCalls.get(i)));
        }
        sb.append("]\n}\n");
        return sb.toString();
    }

    private static StringBuilder kv(StringBuilder sb, String k, String v) {
        return sb.append("  ").append(q(k)).append(": ").append(q(v));
    }

    static String num(double d) {
        if (d == Math.rint(d)) return String.valueOf((long) d);
        return String.format(java.util.Locale.ROOT, "%.2f", d);
    }

    static String rect(Rectangle2D r) {
        if (r == null) return "null";
        return "[" + num(r.getX()) + ", " + num(r.getY()) + ", " + num(r.getWidth()) + ", " + num(r.getHeight()) + "]";
    }

    static String q(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
