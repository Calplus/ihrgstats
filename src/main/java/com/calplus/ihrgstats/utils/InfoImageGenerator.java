package com.calplus.ihrgstats.utils;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Utility class for generating single-entity information images.
 * Used for displaying detailed information about a single player or hall.
 */
public class InfoImageGenerator {
    
    private static final int ROW_HEIGHT = 30;
    private static final int LARGE_ICON_SIZE = 192;
    private static final int PADDING = 20;
    private static final int SECTION_SPACING = 30;
    private static final int HEADER_TO_TABLE_SPACING = 50;
    
    // Background color - light yellow
    private static final Color BACKGROUND = new Color(255, 255, 224);
    
    // Table colors
    private static final Color TABLE_LIGHT = new Color(173, 216, 230);
    private static final Color TABLE_LIGHTER = new Color(224, 255, 255);
    private static final Color TABLE_HIGHLIGHT = new Color(144, 238, 144);  // Light green for highlighting
    
    private static final Color TEXT_COLOR = Color.BLACK;
    private static final Font TABLE_FONT = FontManager.getMonoFont(24);
    private static final Font HEADER_FONT = FontManager.getSansBoldFont(32);
    private static final Font TITLE_FONT = FontManager.getSansBoldFont(48);
    private static final Font METADATA_FONT = FontManager.getSansFont(20);
    
    /**
     * Image metadata
     */
    public static class ImageMetadata {
        public String title;
        public String subtitle;
        public String description;
        public String lastRound;
        public String generatedDate;
        public String secondHallIdentifier;  // Optional: for displaying two halls side-by-side
        
        public ImageMetadata() {
            this.generatedDate = TimezoneHelper.formatNow("yyyy-MM-dd HH:mm:ss");
        }
    }
    
    /**
     * Represents a section of data
     */
    public static class Section {
        public String header;
        public List<Row> rows;
        public List<VictoryEntry> victoryEntries;
        
        public Section(String header) {
            this.header = header;
            this.rows = new ArrayList<>();
            this.victoryEntries = new ArrayList<>();
        }
        
        public void addRow(String label, String value) {
            rows.add(new Row(label, value));
        }
        
        public void addMonospacedRow(String value) {
            rows.add(new Row("", value, true, false));
        }
        
        public void addMonospacedRow(String value, boolean highlight) {
            rows.add(new Row("", value, true, highlight));
        }
        
        public void addVictoryEntry(VictoryEntry entry) {
            victoryEntries.add(entry);
        }
    }
    
    /**
     * Represents a data row
     */
    public static class Row {
        public String label;
        public String value;
        public boolean leftAlign;  // If true, left-align the value (for monospaced format)
        public boolean highlight;  // If true, highlight row in green
        
        public Row(String label, String value) {
            this(label, value, false, false);
        }
        
        public Row(String label, String value, boolean leftAlign) {
            this(label, value, leftAlign, false);
        }
        
        public Row(String label, String value, boolean leftAlign, boolean highlight) {
            this.label = label;
            this.value = value;
            this.leftAlign = leftAlign;
            this.highlight = highlight;
        }
    }
    
    /**
     * Victory record entry
     */
    public static class VictoryEntry {
        public String round;
        public String hallEmoji;        // Outcome emoji for player/hall
        public String playerHall;       // Player's hall (shortened, e.g. "H1")
        public String playerElo;        // Player's ELO as string
        public String playerName;       // Player's name
        public String score;            // Match score (e.g. "1-0", "0.5-0.5")
        public String opponentName;     // Opponent's name
        public String opponentElo;      // Opponent's ELO as string
        public String opponentHall;     // Opponent's hall (shortened, e.g. "H2")
        public String oppEmoji;         // Outcome emoji for opponent
        public boolean isNA;            // True if this round is N/A
        public boolean highlightPlayer; // True if player/left side should be highlighted green
        public boolean highlightOpponent; // True if opponent/right side should be highlighted green
        public Integer hallOutcome;     // Outcome value for images (1=win, 0=draw, -1=loss, null=unknown)
        public Integer oppOutcome;      // Outcome value for images (opponent's outcome)
        
        // Legacy fields for backward compatibility
        public String result;           // Deprecated: use hallEmoji instead
        public int _deprecatedOppElo;   // Deprecated: use opponentElo string instead
    }
    
    /**
     * Generates an information image for a single entity
     */
    public static Path generateInfoImage(ImageMetadata metadata, List<Section> sections, String hallIdentifier) throws IOException {
        return generateInfoImage(metadata, sections, hallIdentifier, "Info", "");
    }
    
    /**
     * Generates an information image for a single entity with custom command and entity names
     */
    public static Path generateInfoImage(ImageMetadata metadata, List<Section> sections, String hallIdentifier, String commandName, String entityName) throws IOException {
        // Create temporary graphics to calculate dimensions
        BufferedImage tempImg = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D tempG2d = tempImg.createGraphics();
        tempG2d.setFont(TABLE_FONT);
        FontMetrics fm = tempG2d.getFontMetrics();
        
        // Calculate header height (pass hallIdentifier to determine if icon is needed)
        int headerHeight = calculateHeaderHeight(tempG2d, metadata, hallIdentifier);
        
        // Calculate content width and height
        int contentWidth = calculateMaxWidth(sections, fm);
        int contentHeight = calculateContentHeight(sections, fm);
        
        // Determine spacing based on image type
        // Match info (hallIdentifier == null) needs minimal spacing
        // Player/hall info needs more spacing to avoid overlap with hall icon
        int headerToTableSpacing = (hallIdentifier == null) ? 5 : 70;
        
        // Total dimensions
        int imageWidth = contentWidth + (PADDING * 2);
        int imageHeight = headerHeight + headerToTableSpacing + contentHeight + PADDING;
        
        tempG2d.dispose();
        
        // Create actual image
        BufferedImage image = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();
        
        // Enable antialiasing
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        
        // Draw tiled background
        drawTiledBackground(g2d, hallIdentifier, 0, 0, imageWidth, imageHeight);
        
        // Draw white background for header
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, imageWidth, headerHeight);
        
        // Draw header
        drawHeaderSection(g2d, metadata, hallIdentifier, imageWidth);
        
        // Draw sections
        int yOffset = headerHeight + headerToTableSpacing;
        for (Section section : sections) {
            yOffset = drawSection(g2d, section, PADDING, yOffset, contentWidth, fm);
            yOffset += SECTION_SPACING;
        }
        
        g2d.dispose();
        
        // Save image with new naming convention
        String timestamp = TimezoneHelper.formatNow("yyMMdd_HHmmss");
        String sanitizedName = entityName.isEmpty() ? "" : sanitizeName(entityName) + "_";
        String filename = String.format("%s_%s%s.png", commandName, sanitizedName, timestamp);
        Path outputPath = Paths.get(System.getProperty("java.io.tmpdir"), filename);
        ImageIO.write(image, "PNG", outputPath.toFile());
        
        return outputPath;
    }
    
    /**
     * Calculates dynamic header height
     */
    private static int calculateHeaderHeight(Graphics2D tempG2d, ImageMetadata metadata, String hallIdentifier) {
        int height = PADDING * 2;
        
        // Title
        tempG2d.setFont(TITLE_FONT);
        FontMetrics titleFm = tempG2d.getFontMetrics();
        height += titleFm.getHeight();
        
        // Metadata font metrics
        tempG2d.setFont(METADATA_FONT);
        FontMetrics metaFm = tempG2d.getFontMetrics();
        
        // Generated date (always present)
        height += metaFm.getHeight() + 10;
        
        // Description (if present)
        if (metadata.description != null && !metadata.description.isEmpty()) {
            height += metaFm.getHeight() + 5;
        }
        
        // Last round (if present)
        if (metadata.lastRound != null && !metadata.lastRound.isEmpty()) {
            height += metaFm.getHeight() + 10;
        }
        
        // For hall/player images: add subtitle and icon
        // For match info: no subtitle or icon
        if (hallIdentifier != null) {
            // Subtitle
            if (metadata.subtitle != null && !metadata.subtitle.isEmpty()) {
                tempG2d.setFont(HEADER_FONT);
                FontMetrics subtitleFm = tempG2d.getFontMetrics();
                height += subtitleFm.getHeight() + 10;
            }
            
            // Icon size
            height += LARGE_ICON_SIZE + 10;
        }
        
        return height;
    }
    
    /**
     * Draws header section with metadata
     */
    private static void drawHeaderSection(Graphics2D g2d, ImageMetadata metadata, String hallIdentifier, int imageWidth) {
        int yOffset = PADDING;
        
        // Draw title
        g2d.setFont(TITLE_FONT);
        g2d.setColor(TEXT_COLOR);
        FontMetrics titleFm = g2d.getFontMetrics();
        int titleWidth = titleFm.stringWidth(metadata.title);
        g2d.drawString(metadata.title, (imageWidth - titleWidth) / 2, yOffset + titleFm.getAscent());
        yOffset += titleFm.getHeight();
        
        // For hall/player images: draw metadata info before subtitle and icon
        if (hallIdentifier != null) {
            // Draw generated date
            g2d.setFont(METADATA_FONT);
            g2d.setColor(Color.BLACK);
            FontMetrics metaFm = g2d.getFontMetrics();
            String dateText = "Generated: " + metadata.generatedDate;
            int dateWidth = metaFm.stringWidth(dateText);
            g2d.drawString(dateText, (imageWidth - dateWidth) / 2, yOffset + metaFm.getAscent() + 10);
            yOffset += metaFm.getHeight() + 5;
            
            // Draw description if available
            if (metadata.description != null && !metadata.description.isEmpty()) {
                String descText = metadata.description;
                int descWidth = metaFm.stringWidth(descText);
                g2d.drawString(descText, (imageWidth - descWidth) / 2, yOffset + metaFm.getAscent() + 5);
                yOffset += metaFm.getHeight() + 5;
            }
            
            // Draw last round if available
            if (metadata.lastRound != null && !metadata.lastRound.isEmpty()) {
                String roundText = "Last Round: " + metadata.lastRound;
                int roundWidth = metaFm.stringWidth(roundText);
                g2d.drawString(roundText, (imageWidth - roundWidth) / 2, yOffset + metaFm.getAscent() + 5);
                yOffset += metaFm.getHeight() + 10;
            }
        }
        
        // Draw hall icon (if available)
        if (hallIdentifier != null) {
            // Check if we need to draw two hall icons side by side
            if (metadata.secondHallIdentifier != null) {
                // Draw two icons side by side
                int iconSpacing = 40;  // Space between icons
                int totalWidth = (LARGE_ICON_SIZE * 2) + iconSpacing;
                int startX = (imageWidth - totalWidth) / 2;
                
                // Draw first hall icon
                try {
                    String iconPath1 = "/halls/" + hallIdentifier.toLowerCase() + ".png";
                    InputStream iconStream1 = InfoImageGenerator.class.getResourceAsStream(iconPath1);
                    
                    if (iconStream1 == null) {
                        iconPath1 = "/halls/unknown.png";
                        iconStream1 = InfoImageGenerator.class.getResourceAsStream(iconPath1);
                    }
                    
                    if (iconStream1 != null) {
                        BufferedImage icon1 = ImageIO.read(iconStream1);
                        g2d.drawImage(icon1, startX, yOffset, LARGE_ICON_SIZE, LARGE_ICON_SIZE, null);
                    }
                } catch (Exception e) {
                    // Icon not found, skip
                }
                
                // Draw second hall icon
                try {
                    String iconPath2 = "/halls/" + metadata.secondHallIdentifier.toLowerCase() + ".png";
                    InputStream iconStream2 = InfoImageGenerator.class.getResourceAsStream(iconPath2);
                    
                    if (iconStream2 == null) {
                        iconPath2 = "/halls/unknown.png";
                        iconStream2 = InfoImageGenerator.class.getResourceAsStream(iconPath2);
                    }
                    
                    if (iconStream2 != null) {
                        BufferedImage icon2 = ImageIO.read(iconStream2);
                        g2d.drawImage(icon2, startX + LARGE_ICON_SIZE + iconSpacing, yOffset, LARGE_ICON_SIZE, LARGE_ICON_SIZE, null);
                    }
                } catch (Exception e) {
                    // Icon not found, skip
                }
                
                yOffset += LARGE_ICON_SIZE + 10;
            } else {
                // Draw single icon (original behavior)
                try {
                    String iconPath = "/halls/" + hallIdentifier.toLowerCase() + ".png";
                    InputStream iconStream = InfoImageGenerator.class.getResourceAsStream(iconPath);
                    
                    // Try unknown.png fallback if icon not found
                    if (iconStream == null) {
                        iconPath = "/halls/unknown.png";
                        iconStream = InfoImageGenerator.class.getResourceAsStream(iconPath);
                    }
                    
                    if (iconStream != null) {
                        BufferedImage icon = ImageIO.read(iconStream);
                        int iconX = (imageWidth - LARGE_ICON_SIZE) / 2;
                        g2d.drawImage(icon, iconX, yOffset, LARGE_ICON_SIZE, LARGE_ICON_SIZE, null);
                        yOffset += LARGE_ICON_SIZE + 10;
                    }
                } catch (Exception e) {
                    // Icon not found, skip
                }
            }
        } else {
            // For match info: draw metadata after subtitle
            g2d.setFont(METADATA_FONT);
            g2d.setColor(Color.BLACK);
            FontMetrics metaFm = g2d.getFontMetrics();
            String dateText = "Generated: " + metadata.generatedDate;
            int dateWidth = metaFm.stringWidth(dateText);
            g2d.drawString(dateText, (imageWidth - dateWidth) / 2, yOffset + metaFm.getAscent() + 10);
            yOffset += metaFm.getHeight() + 5;
            
            // Draw description if available
            if (metadata.description != null && !metadata.description.isEmpty()) {
                String descText = metadata.description;
                int descWidth = metaFm.stringWidth(descText);
                g2d.drawString(descText, (imageWidth - descWidth) / 2, yOffset + metaFm.getAscent() + 5);
                yOffset += metaFm.getHeight() + 5;
            }
            
            // Draw last round if available
            if (metadata.lastRound != null && !metadata.lastRound.isEmpty()) {
                String roundText = "Last Round: " + metadata.lastRound;
                int roundWidth = metaFm.stringWidth(roundText);
                g2d.drawString(roundText, (imageWidth - roundWidth) / 2, yOffset + metaFm.getAscent() + 5);
            }
        }

        // Draw subtitle (hall/player name)
        if (metadata.subtitle != null && !metadata.subtitle.isEmpty()) {
            g2d.setFont(HEADER_FONT);
            g2d.setColor(TEXT_COLOR);
            FontMetrics subtitleFm = g2d.getFontMetrics();
            int subtitleWidth = subtitleFm.stringWidth(metadata.subtitle);
            g2d.drawString(metadata.subtitle, (imageWidth - subtitleWidth) / 2, yOffset + subtitleFm.getAscent() + 10);
            yOffset += subtitleFm.getHeight() + 10;
        }
    }
    
    /**
     * Draws tiled background
     */
    private static void drawTiledBackground(Graphics2D g2d, String hallName, int x, int y, int width, int height) {
        // Fill with solid background color
        g2d.setColor(BACKGROUND);
        g2d.fillRect(x, y, width, height);
        
        // Try to load and tile hall icon as watermark
        try {
            String iconPath = "/halls/" + hallName.toLowerCase() + ".png";
            InputStream iconStream = InfoImageGenerator.class.getResourceAsStream(iconPath);
            
            // Try unknown.png fallback if icon not found
            if (iconStream == null) {
                iconPath = "/halls/unknown.png";
                iconStream = InfoImageGenerator.class.getResourceAsStream(iconPath);
            }
            
            if (iconStream != null) {
                BufferedImage icon = ImageIO.read(iconStream);
                
                // Create semi-transparent version
                BufferedImage tiledIcon = new BufferedImage(icon.getWidth(), icon.getHeight(), 
                                                            BufferedImage.TYPE_INT_ARGB);
                Graphics2D iconG2d = tiledIcon.createGraphics();
                iconG2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.2f));
                iconG2d.drawImage(icon, 0, 0, null);
                iconG2d.dispose();
                
                // Save current transform and clip
                AffineTransform oldTransform = g2d.getTransform();
                Shape oldClip = g2d.getClip();
                
                // Set clip to constrain drawing to this region only
                g2d.setClip(x, y, width, height);
                
                // Rotate 15 degrees around center of this region
                g2d.translate(x + width / 2, y + height / 2);
                g2d.rotate(Math.toRadians(-15));
                g2d.translate(-(x + width / 2), -(y + height / 2));
                
                // Tile the icon (with extra tiles to cover rotation)
                int iconSize = 100;
                int tilesX = (int) Math.ceil((double) width / iconSize) + 4;
                int tilesY = (int) Math.ceil((double) height / iconSize) + 4;
                
                for (int i = -2; i < tilesX; i++) {
                    for (int j = -2; j < tilesY; j++) {
                        int tileX = x + i * iconSize;
                        int tileY = y + j * iconSize;
                        g2d.drawImage(tiledIcon, tileX, tileY, iconSize, iconSize, null);
                    }
                }
                
                // Restore transform and clip
                g2d.setTransform(oldTransform);
                g2d.setClip(oldClip);
            }
        } catch (Exception e) {
            // Ignore - just use solid background
        }
    }
    
    /**
     * Draws a section
     */
    private static int drawSection(Graphics2D g2d, Section section, int x, int y, int width, FontMetrics fm) {
        int yOffset = y;
        
        // Draw section header (centered)
        g2d.setFont(HEADER_FONT);
        g2d.setColor(TEXT_COLOR);
        FontMetrics headerFm = g2d.getFontMetrics();
        int headerWidth = headerFm.stringWidth(section.header);
        g2d.drawString(section.header, x + (width - headerWidth) / 2, yOffset + headerFm.getAscent());
        yOffset += headerFm.getHeight() + 10;
        
        g2d.setFont(TABLE_FONT);
        
        // Draw rows
        boolean alternate = false;
        for (Row row : section.rows) {
            // Draw row background
            if (row.highlight) {
                g2d.setColor(TABLE_HIGHLIGHT);  // Green highlight for home hall
            } else {
                g2d.setColor(alternate ? TABLE_LIGHT : TABLE_LIGHTER);
            }
            g2d.fillRect(x, yOffset, width, ROW_HEIGHT);
            
            // Draw text
            g2d.setColor(TEXT_COLOR);
            // Center text vertically within the row
            int textY = yOffset + (ROW_HEIGHT - fm.getHeight()) / 2 + fm.getAscent();
            
            // Determine how to draw the row
            if (row.leftAlign) {
                // Left-aligned monospaced format (like ComparisonImageGenerator)
                g2d.drawString(row.value, x + 5, textY);
            } else if (row.label == null || row.label.trim().isEmpty()) {
                // Center the value
                int valueWidth = fm.stringWidth(row.value);
                g2d.drawString(row.value, x + (width - valueWidth) / 2, textY);
            } else {
                // Standard two-column layout
                g2d.drawString(row.label, x + 10, textY);
                g2d.drawString(row.value, x + width / 2, textY);
            }
            
            yOffset += ROW_HEIGHT;
            alternate = !alternate;
        }
        
        // Draw victory entries
        for (VictoryEntry entry : section.victoryEntries) {
            yOffset = drawVictoryEntry(g2d, entry, x, yOffset, width, fm, alternate);
            alternate = !alternate;
        }
        
        return yOffset;
    }
    
    /**
     * Draws a victory record entry
     */
    private static int drawVictoryEntry(Graphics2D g2d, VictoryEntry entry, int x, int y, int width, FontMetrics fm, boolean alternate) {
        // Null-safe field initialization
        if (entry.score == null) entry.score = "";
        if (entry.hallEmoji == null) entry.hallEmoji = "";
        if (entry.playerElo == null) entry.playerElo = "";
        if (entry.opponentElo == null) entry.opponentElo = "";
        if (entry.playerHall == null) entry.playerHall = "";
        if (entry.opponentHall == null) entry.opponentHall = "";
        if (entry.playerName == null) entry.playerName = "";
        if (entry.opponentName == null) entry.opponentName = "";
        if (entry.oppEmoji == null) entry.oppEmoji = "";
        if (entry.round == null) entry.round = "";
        
        // Draw row background
        g2d.setColor(alternate ? TABLE_LIGHT : TABLE_LIGHTER);
        g2d.fillRect(x, y, width, ROW_HEIGHT);
        
        // Use fixed-width score column for alignment (max width for scores like "267.7-180.8")
        // Split score at dash and center the dash character
        int fixedScoreColWidth = fm.stringWidth("200.5-100.5");
        int centerX = x + width / 2;
        int scoreColStartX = centerX - fixedScoreColWidth / 2;
        int scoreColEndX = scoreColStartX + fixedScoreColWidth;
        
        // Split score into left and right parts at the dash
        String[] scoreParts = entry.score.split("-", 2);
        String leftScore = scoreParts.length > 0 ? scoreParts[0] : "";
        String rightScore = scoreParts.length > 1 ? scoreParts[1] : "";
        int dashWidth = fm.stringWidth("-");
        
        // Calculate positions to center the dash
        int dashX = centerX - dashWidth / 2;
        int leftScoreWidth = fm.stringWidth(leftScore);
        int leftScoreX = dashX - leftScoreWidth;
        int rightScoreX = dashX + dashWidth;
        
        // Fixed width for round column to ensure vertical alignment
        // If round is empty, don't reserve any space for it
        int roundColWidth = entry.round.isEmpty() ? 0 : fm.stringWidth("T16 ");
        
        // Check if this is a hall victory entry (playerName is empty) or player victory entry
        boolean isHallEntry = (entry.playerName == null || entry.playerName.trim().isEmpty());
        
        // Pre-calculate boundaries for highlighting
        if (isHallEntry) {
            // Hall entry: calculate left and right boundaries
            int leftX = x + roundColWidth;
            int leftEndX = scoreColStartX - 8;
            int rightStartX = scoreColEndX + 8;
            int rightEndX = x + width;
            
            // Draw highlights
            if (entry.highlightPlayer) {
                g2d.setColor(TABLE_HIGHLIGHT);
                g2d.fillRect(leftX, y, leftEndX - leftX, ROW_HEIGHT);
            }
            if (entry.highlightOpponent) {
                g2d.setColor(TABLE_HIGHLIGHT);
                g2d.fillRect(rightStartX, y, rightEndX - rightStartX, ROW_HEIGHT);
            }
        } else {
            // Player entry: calculate left and right boundaries
            int leftX = x + roundColWidth;
            int leftEndX = scoreColStartX - 20;
            int rightStartX = scoreColEndX + 20;
            int rightEndX = x + width;
            
            // Draw highlights
            if (entry.highlightPlayer) {
                g2d.setColor(TABLE_HIGHLIGHT);
                g2d.fillRect(leftX, y, leftEndX - leftX, ROW_HEIGHT);
            }
            if (entry.highlightOpponent) {
                g2d.setColor(TABLE_HIGHLIGHT);
                g2d.fillRect(rightStartX, y, rightEndX - rightStartX, ROW_HEIGHT);
            }
        }
        
        // Draw text
        g2d.setColor(TEXT_COLOR);
        // Center text vertically within the row
        int textY = y + (ROW_HEIGHT - fm.getHeight()) / 2 + fm.getAscent();
        
        // Handle N/A entries
        if (entry.isNA) {
            g2d.drawString(entry.round, x, textY);
            int naWidth = fm.stringWidth("-NA-");
            g2d.drawString("-NA-", x + (width - naWidth) / 2, textY);
            return y + ROW_HEIGHT;
        }
        
        if (isHallEntry) {
            // Hall victory format: round emoji hallElo [hallName] score [oppName] oppElo emoji
            // Draw left flush: round, emoji, hallElo
            int leftX = x;
            g2d.drawString(entry.round, leftX, textY);
            leftX += roundColWidth;
            
            OutcomeIconRenderer.drawOutcomeIcon(g2d, entry.hallOutcome, leftX, textY, TABLE_FONT);
            leftX += OutcomeIconRenderer.getOutcomeIconWidth(g2d, entry.hallOutcome, TABLE_FONT) + 3;
            
            g2d.drawString(entry.playerElo, leftX, textY);
            leftX += fm.stringWidth(entry.playerElo) + 8;
            
            // Draw right flush: oppElo, oppEmoji
            int rightX = x + width;
            rightX -= OutcomeIconRenderer.getOutcomeIconWidth(g2d, entry.oppOutcome, TABLE_FONT);
            OutcomeIconRenderer.drawOutcomeIcon(g2d, entry.oppOutcome, rightX, textY, TABLE_FONT);
            rightX -= 3;
            
            rightX -= fm.stringWidth(entry.opponentElo);
            g2d.drawString(entry.opponentElo, rightX, textY);
            rightX -= 8;
            
            // Draw score with centered dash
            g2d.drawString(leftScore, leftScoreX, textY);
            g2d.drawString("-", dashX, textY);
            g2d.drawString(rightScore, rightScoreX, textY);
            
            // Draw hall names centered around fixed score column
            int hallNameSpace = scoreColStartX - 8 - leftX;
            int oppNameSpace = rightX - (scoreColEndX + 8);
            
            // Hall name (right-justified before score column)
            if (hallNameSpace > 20) {
                String displayName = shortenNameWithInitials(entry.playerHall, hallNameSpace, fm);
                int nameWidth = fm.stringWidth(displayName);
                g2d.drawString(displayName, scoreColStartX - 8 - nameWidth, textY);
            }
            
            // Opponent name (left-justified after score column)
            if (oppNameSpace > 20) {
                String displayName = shortenNameWithInitials(entry.opponentHall, oppNameSpace, fm);
                g2d.drawString(displayName, scoreColEndX + 8, textY);
            }
        } else {
            // Player victory format: round emoji playerHall playerElo [playerName] score [oppName] oppElo oppHall emoji
            // Draw left flush: round, emoji, playerHall, playerElo
            int leftX = x;
            g2d.drawString(entry.round, leftX, textY);
            leftX += roundColWidth;
            
            OutcomeIconRenderer.drawOutcomeIcon(g2d, entry.hallOutcome, leftX, textY, TABLE_FONT);
            leftX += OutcomeIconRenderer.getOutcomeIconWidth(g2d, entry.hallOutcome, TABLE_FONT) + 6;
            
            g2d.drawString(entry.playerHall, leftX, textY);
            leftX += fm.stringWidth(entry.playerHall) + 6;
            
            g2d.drawString(entry.playerElo, leftX, textY);
            leftX += fm.stringWidth(entry.playerElo) + 20;
            
            // Draw right flush: oppElo, oppHall (padded to 3 chars), oppEmoji
            String paddedOppHall = String.format("%3s", entry.opponentHall);
            int rightX = x + width;
            
            rightX -= OutcomeIconRenderer.getOutcomeIconWidth(g2d, entry.oppOutcome, TABLE_FONT);
            OutcomeIconRenderer.drawOutcomeIcon(g2d, entry.oppOutcome, rightX, textY, TABLE_FONT);
            rightX -= 6;
            
            rightX -= fm.stringWidth(paddedOppHall);
            g2d.drawString(paddedOppHall, rightX, textY);
            rightX -= 6;
            
            rightX -= fm.stringWidth(entry.opponentElo);
            g2d.drawString(entry.opponentElo, rightX, textY);
            rightX -= 20;
            
            // Draw score with centered dash
            g2d.drawString(leftScore, leftScoreX, textY);
            g2d.drawString("-", dashX, textY);
            g2d.drawString(rightScore, rightScoreX, textY);
            
            // Calculate available space for names relative to fixed score column
            int playerNameSpace = scoreColStartX - 20 - leftX;
            int oppNameSpace = rightX - (scoreColEndX + 20);
            
            // Draw player name (right-justified before score column)
            if (playerNameSpace > 20) {
                String displayName = shortenNameWithInitials(entry.playerName, playerNameSpace, fm);
                int nameWidth = fm.stringWidth(displayName);
                g2d.drawString(displayName, scoreColStartX - 20 - nameWidth, textY);
            }
            
            // Draw opponent name (left-justified after score column)
            if (oppNameSpace > 20) {
                String displayName = shortenNameWithInitials(entry.opponentName, oppNameSpace, fm);
                g2d.drawString(displayName, scoreColEndX + 20, textY);
            }
        }
        
        return y + ROW_HEIGHT;
    }
    
    /**
     * Shortens a name by converting words to initials until it fits in the available width.
     * Always shortens the longest word first.
     * Example: "Thisisa Verylongfake Name" -> "Thisisa V. Name" -> "T. V. Name"
     */
    private static String shortenNameWithInitials(String name, int availableWidth, FontMetrics fm) {
        // Split name into words
        String[] words = name.split("\\s+");
        if (words.length == 1) {
            // Single word - return as-is if <= 20 chars, otherwise truncate
            if (name.length() <= 20) {
                return name;
            }
            // Truncate to 20 chars with ellipsis
            return name.substring(0, 17) + "...";
        }
        
        // Track which words are already initials
        boolean[] isInitial = new boolean[words.length];
        
        // Keep shortening one word at a time until name is <= 20 characters
        while (true) {
            // Build current name
            StringBuilder current = new StringBuilder();
            for (int i = 0; i < words.length; i++) {
                if (i > 0) current.append(" ");
                current.append(words[i]);
            }
            
            String currentName = current.toString();
            
            // If current name is 20 chars or less, we're done
            if (currentName.length() <= 20) {
                return currentName;
            }
            
            // Find longest non-initial word to shorten
            int longestIndex = -1;
            int longestLength = 0;
            for (int i = 0; i < words.length; i++) {
                if (!isInitial[i] && words[i].length() > longestLength) {
                    longestLength = words[i].length();
                    longestIndex = i;
                }
            }
            
            if (longestIndex == -1) {
                // All words are already initials, can't shorten more
                // Return as-is even if > 20 chars
                return currentName;
            }
            
            // Shorten the longest word to initial
            words[longestIndex] = words[longestIndex].substring(0, 1) + ".";
            isInitial[longestIndex] = true;
        }
    }
    
    /**
     * Calculates maximum width needed for all sections
     */
    private static int calculateMaxWidth(List<Section> sections, FontMetrics fm) {
        int maxWidth = 800;  // Minimum width
        
        for (Section section : sections) {
            for (Row row : section.rows) {
                int rowWidth = fm.stringWidth(row.label) + fm.stringWidth(row.value) + 100;
                maxWidth = Math.max(maxWidth, rowWidth);
            }
            
            for (VictoryEntry entry : section.victoryEntries) {
                if (entry.isNA) {
                    int entryWidth = fm.stringWidth(entry.round + " -NA-") + 50;
                    maxWidth = Math.max(maxWidth, entryWidth);
                } else {
                    // Calculate width for new format: round + emoji + hall + elo + name + score + name + elo + hall + emoji + spacing
                    int roundWidth = fm.stringWidth("T16 ");
                    int emojiWidth = fm.stringWidth(entry.hallEmoji != null ? entry.hallEmoji : "✅") + 6;
                    int hallWidth = fm.stringWidth(entry.playerHall != null ? entry.playerHall : "H1") + 6;
                    int eloWidth = fm.stringWidth(entry.playerElo != null ? entry.playerElo : "1500") + 20;
                    int nameWidth = fm.stringWidth(entry.playerName != null ? shortenNameWithInitials(entry.playerName, 999, fm) : "") + 20;
                    int scoreWidth = fm.stringWidth(entry.score != null ? entry.score : "1-0") + 40;
                    int oppNameWidth = fm.stringWidth(entry.opponentName != null ? shortenNameWithInitials(entry.opponentName, 999, fm) : "") + 20;
                    int oppEloWidth = fm.stringWidth(entry.opponentElo != null ? entry.opponentElo : "1500") + 6;
                    int oppHallWidth = fm.stringWidth(entry.opponentHall != null ? String.format("%3s", entry.opponentHall) : "H1") + 6;
                    int oppEmojiWidth = fm.stringWidth(entry.oppEmoji != null ? entry.oppEmoji : "❌");
                    
                    int entryWidth = roundWidth + emojiWidth + hallWidth + eloWidth + nameWidth + 
                                   scoreWidth + oppNameWidth + oppEloWidth + oppHallWidth + oppEmojiWidth + 60;
                    maxWidth = Math.max(maxWidth, entryWidth);
                }
            }
        }
        
        return maxWidth;
    }
    
    /**
     * Calculates content height for all sections
     */
    private static int calculateContentHeight(List<Section> sections, FontMetrics fm) {
        int height = 0;
        
        Graphics2D tempG2d = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        tempG2d.setFont(HEADER_FONT);
        FontMetrics headerFm = tempG2d.getFontMetrics();
        
        for (Section section : sections) {
            // Header height
            height += headerFm.getHeight() + 10;
            
            // Rows height
            height += (section.rows.size() + section.victoryEntries.size()) * ROW_HEIGHT;
            
            // Section spacing
            height += SECTION_SPACING;
        }
        
        tempG2d.dispose();
        return height;
    }
    
    /**
     * Sanitizes a name for use in a filename by removing invalid characters
     */
    private static String sanitizeName(String name) {
        if (name == null || name.isEmpty()) return "";
        return name.replaceAll("[^a-zA-Z0-9_-]", "_").replaceAll("_{2,}", "_").trim();
    }
}
