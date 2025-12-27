package com.calplus.ihrgstats.utils;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Utility class for generating split comparison images (left vs right).
 * Used for comparing two halls or two players side-by-side.
 */
public class ComparisonImageGenerator {
    
    private static final int ROW_HEIGHT = 30;
    private static final int LARGE_ICON_SIZE = 192;  // For header icons
    private static final int PADDING = 20;
    private static final int SECTION_SPACING = 30;
    private static final int HEADER_TO_TABLE_SPACING = 50;  // Extra spacing between header and tables
    private static final int DIVIDER_WIDTH = 4;
    private static final int HALL_NAME_FONT_SIZE = 28;
    
    // Background colors
    private static final Color BLUE_BACKGROUND = new Color(230, 245, 255);  // Super light blue
    private static final Color RED_BACKGROUND = new Color(255, 240, 240);   // Super light red
    
    // Table colors
    private static final Color BLUE_LIGHT = new Color(173, 216, 230);
    private static final Color BLUE_LIGHTER = new Color(224, 255, 255);
    private static final Color RED_LIGHT = new Color(255, 182, 193);
    private static final Color RED_LIGHTER = new Color(255, 228, 225);
    
    private static final Color TEXT_COLOR = Color.BLACK;
    private static final Color DIVIDER_COLOR = new Color(100, 100, 100);
    private static final Font TABLE_FONT = new Font("Monospaced", Font.PLAIN, 24);
    private static final Font HEADER_FONT = new Font("SansSerif", Font.BOLD, 32);
    private static final Font TITLE_FONT = new Font("SansSerif", Font.BOLD, 48);
    private static final Font METADATA_FONT = new Font("SansSerif", Font.PLAIN, 20);
    private static final Font HALL_NAME_FONT = new Font("SansSerif", Font.BOLD, HALL_NAME_FONT_SIZE);
    
    /**
     * Image metadata
     */
    public static class ImageMetadata {
        public final String title;
        public final String description;
        public final String lastRound;
        
        public ImageMetadata(String title, String description, String lastRound) {
            this.title = title;
            this.description = description;
            this.lastRound = lastRound;
        }
    }
    
    /**
     * Represents data for one side of the comparison
     */
    public static class ComparisonData {
        public String entityName;
        public String hallName;
        public List<Section> sections;
        
        public ComparisonData(String entityName, String hallName, List<Section> sections) {
            this.entityName = entityName;
            this.hallName = hallName;
            this.sections = sections;
        }
    }
    
    /**
     * Represents a section of data
     */
    public static class Section {
        public String title;
        public List<String> lines;
        public boolean centered;  // For victory records
        public boolean flushEmojis;  // For victory records - flush emojis to edges
        
        public Section(String title, List<String> lines) {
            this(title, lines, false, false);
        }
        
        public Section(String title, List<String> lines, boolean centered, boolean flushEmojis) {
            this.title = title;
            this.lines = lines;
            this.centered = centered;
            this.flushEmojis = flushEmojis;
        }
    }
    
    /**
     * Structured data for a victory record entry (Hall format)
     */
    public static class HallVictoryEntry {
        public String round;
        public String hallEmoji;
        public String hallElo;
        public String hallName;
        public String score;
        public String oppName;
        public String oppElo;
        public String oppEmoji;
        public boolean isNA;
        
        public HallVictoryEntry(String round, String hallEmoji, String hallElo, String hallName,
                               String score, String oppName, String oppElo, String oppEmoji) {
            this.round = round;
            this.hallEmoji = hallEmoji;
            this.hallElo = hallElo;
            this.hallName = hallName;
            this.score = score;
            this.oppName = oppName;
            this.oppElo = oppElo;
            this.oppEmoji = oppEmoji;
            this.isNA = false;
        }
        
        public HallVictoryEntry(String round, boolean isNA) {
            this.round = round;
            this.isNA = isNA;
        }
        
        public String toFormattedString() {
            if (isNA) {
                return String.format("%s -NA-", round);
            }
            return String.format("%s %s %s %s %s %s %s %s",
                round, hallEmoji, hallElo, hallName, score, oppName, oppElo, oppEmoji);
        }
    }
    
    /**
     * Structured data for a victory record entry (Player format)
     */
    public static class PlayerVictoryEntry {
        public String round;
        public String hallEmoji;
        public String playerHall;
        public String playerElo;
        public String playerName;
        public String score;
        public String oppName;
        public String oppElo;
        public String oppHall;
        public String oppEmoji;
        public boolean isNA;
        
        public PlayerVictoryEntry(String round, String hallEmoji, String playerHall, String playerElo,
                                 String playerName, String score, String oppName, String oppElo,
                                 String oppHall, String oppEmoji) {
            this.round = round;
            this.hallEmoji = hallEmoji;
            this.playerHall = playerHall;
            this.playerElo = playerElo;
            this.playerName = playerName;
            this.score = score;
            this.oppName = oppName;
            this.oppElo = oppElo;
            this.oppHall = oppHall;
            this.oppEmoji = oppEmoji;
            this.isNA = false;
        }
        
        public PlayerVictoryEntry(String round, boolean isNA) {
            this.round = round;
            this.isNA = isNA;
        }
        
        public String toFormattedString() {
            if (isNA) {
                return String.format("%s -NA-", round);
            }
            return String.format("%s %s %s %s %s %s %s %s %s %s",
                round, hallEmoji, playerHall, playerElo, playerName, score, oppName, oppElo, oppHall, oppEmoji);
        }
    }
    
    /**
     * Generates a comparison image with left and right sides
     */
    public static Path generateComparisonImage(String title, ComparisonData leftData, 
                                              ComparisonData rightData, ImageMetadata metadata) throws IOException {
        // Calculate dimensions
        Graphics2D tempG2d = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
        tempG2d.setFont(TABLE_FONT);
        FontMetrics fm = tempG2d.getFontMetrics();
        
        int leftWidth = calculateMaxWidth(leftData, fm);
        int rightWidth = calculateMaxWidth(rightData, fm);
        int sideWidth = Math.max(leftWidth, rightWidth);
        
        // Calculate content heights (starting from same Y position)
        int leftContentHeight = calculateContentHeight(leftData, fm);
        int rightContentHeight = calculateContentHeight(rightData, fm);
        int contentHeight = Math.max(leftContentHeight, rightContentHeight);
        
        // Calculate dynamic header height
        int headerHeight = calculateHeaderHeight(tempG2d, metadata, leftData.hallName, rightData.hallName);
        
        tempG2d.dispose();
        
        // Image dimensions
        int imageWidth = sideWidth * 2 + DIVIDER_WIDTH + PADDING * 4;
        // Add extra bottom padding (40px) to prevent image from getting cut off
        int imageHeight = headerHeight + contentHeight + PADDING * 2 + 40;
        
        // Create image
        BufferedImage image = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        int leftSideWidth = imageWidth / 2;
        
        // Fill white header
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, imageWidth, headerHeight);
        
        // Fill colored backgrounds below header
        g2d.setColor(BLUE_BACKGROUND);
        g2d.fillRect(0, headerHeight, leftSideWidth, imageHeight - headerHeight);
        g2d.setColor(RED_BACKGROUND);
        g2d.fillRect(leftSideWidth, headerHeight, imageWidth - leftSideWidth, imageHeight - headerHeight);
        
        // Draw tiled backgrounds (constrained to each side)
        drawTiledBackground(g2d, extractHallIdentifier(leftData.hallName), 0, headerHeight, leftSideWidth, 
                           imageHeight - headerHeight);
        drawTiledBackground(g2d, extractHallIdentifier(rightData.hallName), leftSideWidth, headerHeight, 
                           imageWidth - leftSideWidth, imageHeight - headerHeight);
        
        // Draw header section
        drawHeaderSection(g2d, metadata, leftData, rightData, imageWidth, leftSideWidth);
        
        // Draw divider line (only below header)
        g2d.setColor(DIVIDER_COLOR);
        g2d.fillRect(leftSideWidth - DIVIDER_WIDTH / 2, headerHeight, DIVIDER_WIDTH, 
                    imageHeight - headerHeight);
        
        // Draw both sides starting at same Y position
        int startY = headerHeight + HEADER_TO_TABLE_SPACING;
        drawSide(g2d, leftData, PADDING, startY, sideWidth, true);
        drawSide(g2d, rightData, leftSideWidth + DIVIDER_WIDTH / 2 + PADDING, startY, sideWidth, false);
        
        g2d.dispose();
        
        // Save to temp file
        Path tempFile = Files.createTempFile("comparison_", ".png");
        ImageIO.write(image, "PNG", tempFile.toFile());
        
        return tempFile;
    }
    
    /**
     * Calculates dynamic header height including metadata, icons, and hall names
     */
    private static int calculateHeaderHeight(Graphics2D tempG2d, ImageMetadata metadata,
                                            String leftHallName, String rightHallName) {
        int currentY = 20;
        
        // Title
        tempG2d.setFont(TITLE_FONT);
        FontMetrics titleFm = tempG2d.getFontMetrics();
        currentY += titleFm.getHeight() + 10;
        
        // Description
        if (metadata.description != null && !metadata.description.isEmpty()) {
            tempG2d.setFont(METADATA_FONT);
            FontMetrics metaFm = tempG2d.getFontMetrics();
            String[] descLines = metadata.description.split("\n");
            currentY += descLines.length * metaFm.getHeight() + 5;
        }
        
        // Last round
        if (metadata.lastRound != null && !metadata.lastRound.isEmpty()) {
            tempG2d.setFont(METADATA_FONT);
            FontMetrics metaFm = tempG2d.getFontMetrics();
            currentY += metaFm.getHeight();
        }
        
        // Timestamp
        tempG2d.setFont(METADATA_FONT);
        FontMetrics metaFm = tempG2d.getFontMetrics();
        currentY += metaFm.getHeight() + 15;
        
        // Hall icons
        currentY += LARGE_ICON_SIZE + 10;
        
        // Hall names
        tempG2d.setFont(HALL_NAME_FONT);
        FontMetrics hallNameFm = tempG2d.getFontMetrics();
        currentY += hallNameFm.getHeight() + 20;
        
        return currentY;
    }
    
    /**
     * Draws header section with metadata and hall icons
     */
    private static void drawHeaderSection(Graphics2D g2d, ImageMetadata metadata,
                                         ComparisonData leftData, ComparisonData rightData,
                                         int imageWidth, int leftSideWidth) {
        int currentY = 20;
        
        // Draw title
        g2d.setFont(TITLE_FONT);
        g2d.setColor(TEXT_COLOR);
        FontMetrics titleFm = g2d.getFontMetrics();
        int titleWidth = titleFm.stringWidth(metadata.title);
        g2d.drawString(metadata.title, (imageWidth - titleWidth) / 2, currentY + titleFm.getAscent());
        currentY += titleFm.getHeight() + 10;
        
        // Draw description if provided
        if (metadata.description != null && !metadata.description.isEmpty()) {
            g2d.setFont(METADATA_FONT);
            FontMetrics metaFm = g2d.getFontMetrics();
            String[] descLines = metadata.description.split("\n");
            for (String line : descLines) {
                int lineWidth = metaFm.stringWidth(line);
                g2d.drawString(line, (imageWidth - lineWidth) / 2, currentY + metaFm.getAscent());
                currentY += metaFm.getHeight();
            }
            currentY += 5;
        }
        
        // Draw last round
        if (metadata.lastRound != null && !metadata.lastRound.isEmpty()) {
            g2d.setFont(METADATA_FONT);
            FontMetrics metaFm = g2d.getFontMetrics();
            String roundText = "Last Round: " + metadata.lastRound;
            int roundWidth = metaFm.stringWidth(roundText);
            g2d.drawString(roundText, (imageWidth - roundWidth) / 2, currentY + metaFm.getAscent());
            currentY += metaFm.getHeight();
        }
        
        // Draw generation timestamp
        g2d.setFont(METADATA_FONT);
        FontMetrics metaFm = g2d.getFontMetrics();
        String timestamp = "Generated: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        int timestampWidth = metaFm.stringWidth(timestamp);
        g2d.drawString(timestamp, (imageWidth - timestampWidth) / 2, currentY + metaFm.getAscent());
        currentY += metaFm.getHeight() + 15;
        
        // Draw hall icons
        BufferedImage leftIcon = loadHallIcon(extractHallIdentifier(leftData.hallName));
        BufferedImage rightIcon = loadHallIcon(extractHallIdentifier(rightData.hallName));
        
        if (leftIcon != null) {
            int leftIconX = leftSideWidth / 2 - LARGE_ICON_SIZE / 2;
            g2d.drawImage(leftIcon, leftIconX, currentY, LARGE_ICON_SIZE, LARGE_ICON_SIZE, null);
        }
        
        if (rightIcon != null) {
            int rightIconX = leftSideWidth + (imageWidth - leftSideWidth) / 2 - LARGE_ICON_SIZE / 2;
            g2d.drawImage(rightIcon, rightIconX, currentY, LARGE_ICON_SIZE, LARGE_ICON_SIZE, null);
        }
        
        currentY += LARGE_ICON_SIZE + 10;
        
        // Draw hall names below icons
        g2d.setFont(HALL_NAME_FONT);
        g2d.setColor(TEXT_COLOR);
        FontMetrics hallNameFm = g2d.getFontMetrics();
        
        String leftHallLabel = formatHallName(leftData.hallName);
        String rightHallLabel = formatHallName(rightData.hallName);
        
        int leftLabelWidth = hallNameFm.stringWidth(leftHallLabel);
        int rightLabelWidth = hallNameFm.stringWidth(rightHallLabel);
        
        g2d.drawString(leftHallLabel, leftSideWidth / 2 - leftLabelWidth / 2, currentY + hallNameFm.getAscent());
        g2d.drawString(rightHallLabel, leftSideWidth + (imageWidth - leftSideWidth) / 2 - rightLabelWidth / 2, 
                      currentY + hallNameFm.getAscent());
    }
    
    /**
     * Formats hall name with "Hall" prefix/suffix
     * Numeric halls: "Hall 4"
     * Non-numeric halls: "Binjai Hall"
     */
    private static String formatHallName(String hallName) {
        // If hallName contains parentheses, it's already formatted (e.g., "Name (Hall 4)")
        if (hallName.contains("(")) {
            return hallName;
        }
        try {
            Integer.parseInt(hallName);
            return "Hall " + hallName;
        } catch (NumberFormatException e) {
            return hallName + " Hall";
        }
    }
    
    /**
     * Extracts the hall identifier from a subtitle string like "Name (Hall 4)" or "Name (Binjai Hall)"
     * Returns just "4" or "binjai" for hall icon loading
     */
    private static String extractHallIdentifier(String subtitle) {
        // If subtitle contains parentheses, extract what's inside
        if (subtitle.contains("(") && subtitle.contains(")")) {
            int start = subtitle.indexOf("(") + 1;
            int end = subtitle.indexOf(")");
            String hallPart = subtitle.substring(start, end).trim();
            
            // Remove "Hall" prefix/suffix if present
            hallPart = hallPart.replace("Hall ", "").replace(" Hall", "").trim();
            
            return hallPart;
        }
        
        // Otherwise, assume it's just the hall identifier
        return subtitle.trim();
    }

    
    /**
     * Draws tiled background pattern (constrained to specified region)
     */
    private static void drawTiledBackground(Graphics2D g2d, String hallName, int x, int y, 
                                           int width, int height) {
        BufferedImage icon = loadHallIcon(hallName);
        if (icon == null) return;
        
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
        
        // Set clip to constrain drawing to this side only
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
    
    /**
     * Draws one side of the comparison
     */
    private static void drawSide(Graphics2D g2d, ComparisonData data, int x, int y, 
                                int width, boolean isLeft) {
        int currentY = y;
        
        // Draw sections
        g2d.setFont(TABLE_FONT);
        FontMetrics fm = g2d.getFontMetrics();
        
        for (Section section : data.sections) {
            // Draw section title (centered)
            g2d.setFont(HEADER_FONT);
            g2d.setColor(TEXT_COLOR);
            FontMetrics headerFm = g2d.getFontMetrics();
            int titleWidth = headerFm.stringWidth(section.title);
            g2d.drawString(section.title, x + (width - titleWidth) / 2, currentY + headerFm.getAscent());
            currentY += headerFm.getHeight() + 10;  // Add extra spacing after title
            
            // Draw section lines
            g2d.setFont(TABLE_FONT);
            fm = g2d.getFontMetrics();
            
            for (int i = 0; i < section.lines.size(); i++) {
                String line = section.lines.get(i);
                
                // Only draw row background if line is not empty (for buffer rows)
                if (!line.trim().isEmpty()) {
                    Color rowColor = getRowColor(i, isLeft);
                    g2d.setColor(rowColor);
                    g2d.fillRect(x, currentY, width, ROW_HEIGHT);
                }
                
                g2d.setColor(TEXT_COLOR);
                
                // Handle different formatting modes
                if (line.trim().isEmpty()) {
                    // Skip empty buffer rows
                } else if (section.flushEmojis) {
                    // Victory record section - handle both regular matches and -NA- entries
                    drawVictoryRecordLine(g2d, line, x, currentY + fm.getAscent() + 5, width, fm, isLeft);
                } else if (line.trim().equals("-NA-")) {
                    // Center standalone -NA-
                    int lineWidth = fm.stringWidth(line);
                    g2d.drawString(line, x + (width - lineWidth) / 2, currentY + fm.getAscent() + 5);
                } else if (section.centered) {
                    // Centered line
                    int lineWidth = fm.stringWidth(line);
                    g2d.drawString(line, x + (width - lineWidth) / 2, currentY + fm.getAscent() + 5);
                } else {
                    // Left-aligned
                    g2d.drawString(line, x + 5, currentY + fm.getAscent() + 5);
                }
                
                currentY += ROW_HEIGHT;
            }
            
            currentY += SECTION_SPACING;
        }
    }
    
    /**
     * Draws a victory record line using structured parsing.
     * Automatically detects hall vs player format and renders appropriately.
     */
    private static void drawVictoryRecordLine(Graphics2D g2d, String line, int x, int y, 
                                             int width, FontMetrics fm, boolean isLeft) {
        String trimmed = line.trim();
        g2d.setColor(TEXT_COLOR);
        
        // Handle -NA- format
        if (trimmed.contains("-NA-")) {
            drawNALine(g2d, trimmed, x, y, width, fm);
            return;
        }
        
        // Parse the line
        String[] parts = trimmed.split("\\s+");
        if (parts.length < 6) {
            g2d.drawString(trimmed, x + 5, y);
            return;
        }
        
        // Find score index
        int scoreIndex = findScoreIndex(parts);
        if (scoreIndex == -1) {
            g2d.drawString(trimmed, x + 5, y);
            return;
        }
        
        // Determine format and draw
        if (parts.length == 10 && scoreIndex == 5) {
            drawPlayerVictoryLine(g2d, parts, x, y, width, fm);
        } else if (parts.length == 8 && scoreIndex == 4) {
            drawHallVictoryLine(g2d, parts, x, y, width, fm);
        } else {
            g2d.drawString(trimmed, x + 5, y);
        }
    }
    
    /**
     * Draws -NA- format line
     */
    private static void drawNALine(Graphics2D g2d, String line, int x, int y, int width, FontMetrics fm) {
        String[] parts = line.split("\\s+");
        if (parts.length >= 2) {
            String round = parts[0];
            String naText = "-NA-";
            int totalWidth = fm.stringWidth(round + " " + naText);
            int centerX = x + width / 2;
            int startX = centerX - totalWidth / 2;
            g2d.drawString(round, startX, y);
            g2d.drawString(naText, startX + fm.stringWidth(round + " "), y);
        } else {
            int lineWidth = fm.stringWidth(line);
            g2d.drawString(line, x + (width - lineWidth) / 2, y);
        }
    }
    
    /**
     * Finds the score index in parts array
     */
    private static int findScoreIndex(String[] parts) {
        for (int i = 2; i < parts.length; i++) {
            if (parts[i].contains("-") && !parts[i].equals("-NA-")) {
                return i;
            }
        }
        return -1;
    }
    
    /**
     * Draws player victory record line
     * Format: round emoji playerHall playerElo playerName score oppName oppElo oppHall oppEmoji
     */
    private static void drawPlayerVictoryLine(Graphics2D g2d, String[] parts, int x, int y, 
                                             int width, FontMetrics fm) {
        // Extract parts
        String round = parts[0];
        String emoji = parts[1];
        String playerHall = parts[2];
        String playerElo = parts[3];
        String playerName = parts[4];
        String score = parts[5];
        String oppName = parts[6];
        String oppElo = parts[7];
        String oppHall = parts[8];
        String oppEmoji = parts[9];
        
        // Fixed widths for alignment
        int roundColWidth = fm.stringWidth("T16 ");
        
        // Draw left flush: round, emoji, playerHall, playerElo
        int leftX = x + 5;
        g2d.drawString(String.format("%-3s", round), leftX, y);
        leftX += roundColWidth;
        
        g2d.drawString(emoji, leftX, y);
        leftX += fm.stringWidth(emoji) + 3;
        
        g2d.drawString(playerHall, leftX, y);
        leftX += fm.stringWidth(playerHall) + 3;
        
        g2d.drawString(playerElo, leftX, y);
        leftX += fm.stringWidth(playerElo) + 8;
        
        // Draw right flush: oppElo, oppHall (padded), oppEmoji
        String paddedOppHall = String.format("%3s", oppHall);
        int rightX = x + width - 5;
        
        rightX -= fm.stringWidth(oppEmoji);
        g2d.drawString(oppEmoji, rightX, y);
        rightX -= 3;
        
        rightX -= fm.stringWidth(paddedOppHall);
        g2d.drawString(paddedOppHall, rightX, y);
        rightX -= 3;
        
        rightX -= fm.stringWidth(oppElo);
        g2d.drawString(oppElo, rightX, y);
        rightX -= 8;
        
        // Draw center: playerName, score, oppName
        int centerX = x + width / 2;
        int scoreWidth = fm.stringWidth(score);
        int scoreX = centerX - scoreWidth / 2;
        g2d.drawString(score, scoreX, y);
        
        // Draw names with truncation
        int playerNameSpace = scoreX - 8 - leftX;
        if (playerNameSpace > 20) {
            String displayName = shortenNameWithInitials(playerName, playerNameSpace, fm);
            int nameWidth = fm.stringWidth(displayName);
            g2d.drawString(displayName, scoreX - 8 - nameWidth, y);
        }
        
        int oppNameSpace = rightX - (scoreX + scoreWidth + 8);
        if (oppNameSpace > 20) {
            String displayName = shortenNameWithInitials(oppName, oppNameSpace, fm);
            g2d.drawString(displayName, scoreX + scoreWidth + 8, y);
        }
    }
    
    /**
     * Draws hall victory record line
     * Format: round emoji hallElo hallName score oppName oppElo oppEmoji
     */
    private static void drawHallVictoryLine(Graphics2D g2d, String[] parts, int x, int y,
                                           int width, FontMetrics fm) {
        // Extract parts
        String round = parts[0];
        String emoji = parts[1];
        String hallElo = parts[2];
        String hallName = parts[3];
        String score = parts[4];
        String oppName = parts[5];
        String oppElo = parts[6];
        String oppEmoji = parts[7];
        
        // Fixed widths for alignment
        int roundColWidth = fm.stringWidth("T16 ");
        
        // Draw left flush: round, emoji, hallElo
        int leftX = x + 5;
        g2d.drawString(String.format("%-3s", round), leftX, y);
        leftX += roundColWidth;
        
        g2d.drawString(emoji, leftX, y);
        leftX += fm.stringWidth(emoji) + 3;
        
        g2d.drawString(hallElo, leftX, y);
        leftX += fm.stringWidth(hallElo) + 8;
        
        // Draw right flush: oppElo, oppEmoji
        int rightX = x + width - 5;
        
        rightX -= fm.stringWidth(oppEmoji);
        g2d.drawString(oppEmoji, rightX, y);
        rightX -= 3;
        
        rightX -= fm.stringWidth(oppElo);
        g2d.drawString(oppElo, rightX, y);
        rightX -= 8;
        
        // Draw center: hallName, score, oppName
        int centerX = x + width / 2;
        int scoreWidth = fm.stringWidth(score);
        int scoreX = centerX - scoreWidth / 2;
        g2d.drawString(score, scoreX, y);
        
        // Draw names with truncation
        int hallNameSpace = scoreX - 8 - leftX;
        if (hallNameSpace > 20) {
            String displayName = shortenNameWithInitials(hallName, hallNameSpace, fm);
            int nameWidth = fm.stringWidth(displayName);
            g2d.drawString(displayName, scoreX - 8 - nameWidth, y);
        }
        
        int oppNameSpace = rightX - (scoreX + scoreWidth + 8);
        if (oppNameSpace > 20) {
            String displayName = shortenNameWithInitials(oppName, oppNameSpace, fm);
            g2d.drawString(displayName, scoreX + scoreWidth + 8, y);
        }
    }
    
    /**
     * Shortens a name by converting words to initials until it fits in the available width.
     * Always shortens the longest word first.
     * Example: "Thisisa Verylongfake Name" -> "Thisisa V. Name" -> "T. V. Name"
     */
    private static String shortenNameWithInitials(String name, int availableWidth, FontMetrics fm) {
        if (fm.stringWidth(name) <= availableWidth) {
            return name;
        }
        
        // Split name into words
        String[] words = name.split("\\s+");
        if (words.length == 1) {
            // Single word - just truncate with ellipsis
            String shortened = name;
            while (fm.stringWidth(shortened + "...") > availableWidth && shortened.length() > 1) {
                shortened = shortened.substring(0, shortened.length() - 1);
            }
            return shortened + "...";
        }
        
        // Track which words are already initials
        boolean[] isInitial = new boolean[words.length];
        
        // Keep shortening until it fits or all words are initials
        while (true) {
            // Check if current name fits
            StringBuilder current = new StringBuilder();
            for (int i = 0; i < words.length; i++) {
                if (i > 0) current.append(" ");
                current.append(words[i]);
            }
            
            if (fm.stringWidth(current.toString()) <= availableWidth) {
                return current.toString();
            }
            
            // Find longest non-initial word
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
                return current.toString();
            }
            
            // Shorten the longest word to initial
            words[longestIndex] = words[longestIndex].substring(0, 1) + ".";
            isInitial[longestIndex] = true;
        }
    }
    
    /**
     * Calculates maximum width needed for data
     */
    private static int calculateMaxWidth(ComparisonData data, FontMetrics fm) {
        int maxWidth = 600;
        
        Graphics2D tempG2d = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
        tempG2d.setFont(HEADER_FONT);
        FontMetrics headerFm = tempG2d.getFontMetrics();
        maxWidth = Math.max(maxWidth, headerFm.stringWidth(data.entityName) + PADDING * 2);
        tempG2d.dispose();
        
        for (Section section : data.sections) {
            for (String line : section.lines) {
                int lineWidth = fm.stringWidth(line) + 40;
                maxWidth = Math.max(maxWidth, lineWidth);
            }
        }
        
        return Math.min(maxWidth, 900);
    }
    
    /**
     * Calculates content height (without icons which are in header)
     */
    private static int calculateContentHeight(ComparisonData data, FontMetrics fm) {
        int height = 0;
        
        Graphics2D tempG2d = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
        tempG2d.setFont(HEADER_FONT);
        FontMetrics headerFm = tempG2d.getFontMetrics();
        
        for (Section section : data.sections) {
            height += headerFm.getHeight() + 5;
            height += section.lines.size() * ROW_HEIGHT;
            height += SECTION_SPACING;
        }
        
        tempG2d.dispose();
        return height;
    }
    
    /**
     * Gets row background color
     */
    private static Color getRowColor(int rowIndex, boolean isLeft) {
        boolean isEven = rowIndex % 2 == 0;
        if (isLeft) {
            return isEven ? BLUE_LIGHTER : BLUE_LIGHT;
        } else {
            return isEven ? RED_LIGHTER : RED_LIGHT;
        }
    }
    
    /**
     * Loads a hall icon from resources/halls folder
     */
    private static BufferedImage loadHallIcon(String hallName) {
        try {
            String iconPath = "/halls/" + hallName.toLowerCase() + ".png";
            InputStream is = ComparisonImageGenerator.class.getResourceAsStream(iconPath);
            
            if (is != null) {
                return ImageIO.read(is);
            }
            
            // Try unknown.png as fallback
            is = ComparisonImageGenerator.class.getResourceAsStream("/halls/unknown.png");
            if (is != null) {
                return ImageIO.read(is);
            }
            
            return null;
        } catch (IOException e) {
            return null;
        }
    }
}
