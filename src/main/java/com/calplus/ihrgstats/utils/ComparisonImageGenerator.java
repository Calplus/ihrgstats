package com.calplus.ihrgstats.utils;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
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
    private static final Font TABLE_FONT = FontManager.getMonoFont(24);
    private static final Font HEADER_FONT = FontManager.getSansBoldFont(32);
    private static final Font TITLE_FONT = FontManager.getSansBoldFont(48);
    private static final Font METADATA_FONT = FontManager.getSansFont(20);
    private static final Font HALL_NAME_FONT = FontManager.getSansBoldFont(28);
    
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
        public List<HallVictoryEntry> hallVictoryEntries;  // Structured hall victory data
        public List<PlayerVictoryEntry> playerVictoryEntries;  // Structured player victory data
        
        public Section(String title, List<String> lines) {
            this(title, lines, false, false);
        }
        
        public Section(String title, List<String> lines, boolean centered, boolean flushEmojis) {
            this.title = title;
            this.lines = lines;
            this.centered = centered;
            this.flushEmojis = flushEmojis;
            this.hallVictoryEntries = null;
            this.playerVictoryEntries = null;
        }
        
        // Factory method for hall victory records
        public static Section forHallVictory(String title, List<HallVictoryEntry> hallVictoryEntries) {
            Section section = new Section(title, (List<String>)null, false, true);
            section.hallVictoryEntries = hallVictoryEntries;
            return section;
        }
        
        // Factory method for player victory records
        public static Section forPlayerVictory(String title, List<PlayerVictoryEntry> playerVictoryEntries) {
            Section section = new Section(title, (List<String>)null, false, true);
            section.playerVictoryEntries = playerVictoryEntries;
            return section;
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
        public Integer hallOutcome;  // For image rendering (1=win, 0=draw, -1=loss, null=unknown)
        public Integer oppOutcome;   // For image rendering
        
        public HallVictoryEntry(String round, String hallEmoji, String hallElo, String hallName,
                                String score, String oppName, String oppElo, String oppEmoji, 
                                Integer hallOutcome, Integer oppOutcome) {
            this.round = round;
            this.hallEmoji = hallEmoji;
            this.hallElo = hallElo;
            this.hallName = hallName;
            this.score = score;
            this.oppName = oppName;
            this.oppElo = oppElo;
            this.oppEmoji = oppEmoji;
            this.hallOutcome = hallOutcome;
            this.oppOutcome = oppOutcome;
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
        public Integer hallOutcome;  // For image rendering (1=win, 0=draw, -1=loss, null=unknown)
        public Integer oppOutcome;   // For image rendering
        
        public PlayerVictoryEntry(String round, String hallEmoji, String playerHall, String playerElo,
                                 String playerName, String score, String oppName, String oppElo,
                                 String oppHall, String oppEmoji, Integer hallOutcome, Integer oppOutcome) {
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
            this.hallOutcome = hallOutcome;
            this.oppOutcome = oppOutcome;
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
        return generateComparisonImage(title, leftData, rightData, metadata, "Compare", "", "");
    }
    
    /**
     * Generates a comparison image with left and right sides with custom command and entity names
     */
    public static Path generateComparisonImage(String title, ComparisonData leftData, 
                                              ComparisonData rightData, ImageMetadata metadata,
                                              String commandName, String leftEntityName, String rightEntityName) throws IOException {
        // Calculate dimensions
        Graphics2D tempG2d = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
        tempG2d.setFont(TABLE_FONT);
        FontMetrics fm = tempG2d.getFontMetrics();
        tempG2d.dispose(); // FontMetrics stays valid after dispose

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
        
        // Save to temp file with new naming convention
        String timestamp = TimezoneHelper.formatNow("yyMMdd_HHmmss");
        String leftName = ImageRenderSupport.sanitizeName(leftEntityName);
        String rightName = ImageRenderSupport.sanitizeName(rightEntityName);
        
        // Build filename based on what entity names are provided
        String filename;
        if (!leftName.isEmpty() && !rightName.isEmpty()) {
            filename = String.format("%s_%s_%s_%s.png", commandName, leftName, rightName, timestamp);
        } else if (!leftName.isEmpty()) {
            filename = String.format("%s_%s_%s.png", commandName, leftName, timestamp);
        } else if (!rightName.isEmpty()) {
            filename = String.format("%s_%s_%s.png", commandName, rightName, timestamp);
        } else {
            filename = String.format("%s_%s.png", commandName, timestamp);
        }
        
        // Save to the shared, dedicated output directory (not the OS temp
        // dir - nothing was ever cleaning that up, so images accumulated
        // there indefinitely with no inspectable, intentional home).
        Path tempFile = OutputPaths.getOutputDirectory().resolve(filename);
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
        String timestamp = "Generated: " + TimezoneHelper.formatNow("yyyy-MM-dd HH:mm:ss");
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
        ImageRenderSupport.tileIconWatermark(g2d, icon, x, y, width, height);
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
            
            // Draw section content
            g2d.setFont(TABLE_FONT);
            fm = g2d.getFontMetrics();
            
            // Check if this is a structured victory record section
            if (section.hallVictoryEntries != null) {
                // Round-column width must fit the WIDEST round label actually
                // present (Swiss rounds render as "Round 9", not just the
                // short bracket-stage labels like "T16" this used to assume) -
                // a fixed guess sized only for "T16 " lets the outcome icon
                // get drawn straight on top of a longer label.
                int roundColWidth = 0;
                for (HallVictoryEntry e : section.hallVictoryEntries) {
                    if (e.round != null && !e.round.isEmpty()) {
                        roundColWidth = Math.max(roundColWidth, fm.stringWidth(e.round));
                    }
                }
                if (roundColWidth > 0) {
                    roundColWidth += 6;
                }
                // Render hall victory records from structured data
                for (int i = 0; i < section.hallVictoryEntries.size(); i++) {
                    HallVictoryEntry entry = section.hallVictoryEntries.get(i);

                    // Draw row background if not empty
                    if (!entry.isNA || entry.round != null) {
                        Color rowColor = getRowColor(i, isLeft);
                        g2d.setColor(rowColor);
                        g2d.fillRect(x, currentY, width, ROW_HEIGHT);
                    }

                    g2d.setColor(TEXT_COLOR);
                    int textY = currentY + (ROW_HEIGHT - fm.getHeight()) / 2 + fm.getAscent();
                    drawHallVictoryEntry(g2d, entry, x, textY, width, fm, roundColWidth);
                    currentY += ROW_HEIGHT;
                }
            } else if (section.playerVictoryEntries != null) {
                int roundColWidth = 0;
                for (PlayerVictoryEntry e : section.playerVictoryEntries) {
                    if (e.round != null && !e.round.isEmpty()) {
                        roundColWidth = Math.max(roundColWidth, fm.stringWidth(e.round));
                    }
                }
                if (roundColWidth > 0) {
                    roundColWidth += 6;
                }
                // Render player victory records from structured data
                for (int i = 0; i < section.playerVictoryEntries.size(); i++) {
                    PlayerVictoryEntry entry = section.playerVictoryEntries.get(i);

                    // Draw row background if not empty
                    if (!entry.isNA || entry.round != null) {
                        Color rowColor = getRowColor(i, isLeft);
                        g2d.setColor(rowColor);
                        g2d.fillRect(x, currentY, width, ROW_HEIGHT);
                    }

                    g2d.setColor(TEXT_COLOR);
                    int textY = currentY + (ROW_HEIGHT - fm.getHeight()) / 2 + fm.getAscent();
                    drawPlayerVictoryEntry(g2d, entry, x, textY, width, fm, roundColWidth);
                    currentY += ROW_HEIGHT;
                }
            } else if (section.lines != null) {
                // Render traditional string-based lines
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
                    } else if (line.trim().equals("-NA-")) {
                        // Center standalone -NA-
                        int lineWidth = fm.stringWidth(line);
                        int textY = currentY + (ROW_HEIGHT - fm.getHeight()) / 2 + fm.getAscent();
                        g2d.drawString(line, x + (width - lineWidth) / 2, textY);
                    } else if (section.centered) {
                        // Centered line
                        int lineWidth = fm.stringWidth(line);
                        int textY = currentY + (ROW_HEIGHT - fm.getHeight()) / 2 + fm.getAscent();
                        g2d.drawString(line, x + (width - lineWidth) / 2, textY);
                    } else {
                        // Left-aligned
                        int textY = currentY + (ROW_HEIGHT - fm.getHeight()) / 2 + fm.getAscent();
                        g2d.drawString(line, x + 5, textY);
                    }
                    
                    currentY += ROW_HEIGHT;
                }
            }
            
            currentY += SECTION_SPACING;
        }
    }
    
    
    /**
     * Draws a hall victory record entry using structured data
     */
    private static void drawHallVictoryEntry(Graphics2D g2d, HallVictoryEntry entry, int x, int y,
                                            int width, FontMetrics fm, int roundColWidth) {
        if (entry.isNA) {
            // Draw round left-justified, -NA- centered separately
            g2d.drawString(entry.round, x + 5, y);
            int naWidth = fm.stringWidth("-NA-");
            g2d.drawString("-NA-", x + (width - naWidth) / 2, y);
            return;
        }

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
        
        // Draw left flush: round, emoji, hallElo
        int leftX = x + 5;
        g2d.drawString(entry.round, leftX, y);
        leftX += roundColWidth;
        
        OutcomeIconRenderer.drawOutcomeIcon(g2d, entry.hallOutcome, leftX, y, TABLE_FONT);
        leftX += OutcomeIconRenderer.getOutcomeIconWidth(g2d, entry.hallOutcome, TABLE_FONT) + 3;
        
        g2d.drawString(entry.hallElo, leftX, y);
        leftX += fm.stringWidth(entry.hallElo) + 8;
        
        // Draw right flush: oppElo, oppEmoji
        int rightX = x + width - 5;
        rightX -= OutcomeIconRenderer.getOutcomeIconWidth(g2d, entry.oppOutcome, TABLE_FONT);
        OutcomeIconRenderer.drawOutcomeIcon(g2d, entry.oppOutcome, rightX, y, TABLE_FONT);
        rightX -= 3;
        
        rightX -= fm.stringWidth(entry.oppElo);
        g2d.drawString(entry.oppElo, rightX, y);
        rightX -= 8;
        
        // Draw score with centered dash
        g2d.drawString(leftScore, leftScoreX, y);
        g2d.drawString("-", dashX, y);
        g2d.drawString(rightScore, rightScoreX, y);
        
        // Draw hall names, using the ACTUAL drawn score extents (not the
        // fixed-template scoreColStartX/EndX) as the safe edge - a score
        // wider than the "200.5-100.5" template fixedScoreColWidth was sized
        // for would otherwise let a name get drawn directly against (or
        // inside) the score text with no gap.
        int scoreLeftEdge = Math.min(scoreColStartX, leftScoreX);
        int scoreRightEdge = Math.max(scoreColEndX, rightScoreX + fm.stringWidth(rightScore));
        int hallNameSpace = scoreLeftEdge - 8 - leftX;
        int oppNameSpace = rightX - (scoreRightEdge + 8);

        // Hall name (right-justified before score column)
        if (hallNameSpace > 20) {
            String displayName = ImageRenderSupport.shortenNameWithInitials(entry.hallName, hallNameSpace, fm);
            int nameWidth = fm.stringWidth(displayName);
            g2d.drawString(displayName, scoreLeftEdge - 8 - nameWidth, y);
        }

        // Opponent name (left-justified after score column)
        if (oppNameSpace > 20) {
            String displayName = ImageRenderSupport.shortenNameWithInitials(entry.oppName, oppNameSpace, fm);
            g2d.drawString(displayName, scoreRightEdge + 8, y);
        }
    }
    
    /**
     * Draws a player victory record entry using structured data
     */
    private static void drawPlayerVictoryEntry(Graphics2D g2d, PlayerVictoryEntry entry, int x, int y,
                                              int width, FontMetrics fm, int roundColWidth) {
        if (entry.isNA) {
            // Draw round left-justified, -NA- centered separately
            g2d.drawString(entry.round, x + 5, y);
            int naWidth = fm.stringWidth("-NA-");
            g2d.drawString("-NA-", x + (width - naWidth) / 2, y);
            return;
        }

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
        
        // Draw left flush: round, emoji, playerHall, playerElo
        int leftX = x + 5;
        g2d.drawString(entry.round, leftX, y);
        leftX += roundColWidth;
        
        OutcomeIconRenderer.drawOutcomeIcon(g2d, entry.hallOutcome, leftX, y, TABLE_FONT);
        leftX += OutcomeIconRenderer.getOutcomeIconWidth(g2d, entry.hallOutcome, TABLE_FONT) + 6;  // Increased from 3 to 6
        
        g2d.drawString(entry.playerHall, leftX, y);
        leftX += fm.stringWidth(entry.playerHall) + 6;  // Increased from 3 to 6
        
        g2d.drawString(entry.playerElo, leftX, y);
        leftX += fm.stringWidth(entry.playerElo) + 20;  // Increased from 8 to 20 for "a lot more spacing"
        
        // Draw right flush: oppElo, oppHall (padded to 3 chars), oppEmoji
        String paddedOppHall = String.format("%3s", entry.oppHall);
        int rightX = x + width - 5;
        
        rightX -= OutcomeIconRenderer.getOutcomeIconWidth(g2d, entry.oppOutcome, TABLE_FONT);
        OutcomeIconRenderer.drawOutcomeIcon(g2d, entry.oppOutcome, rightX, y, TABLE_FONT);
        rightX -= 6;  // Increased from 3 to 6
        
        rightX -= fm.stringWidth(paddedOppHall);
        g2d.drawString(paddedOppHall, rightX, y);
        rightX -= 6;  // Increased from 3 to 6
        
        rightX -= fm.stringWidth(entry.oppElo);
        g2d.drawString(entry.oppElo, rightX, y);
        rightX -= 20;  // Increased from 8 to 20 for "a lot more spacing"
        
        // Draw score with centered dash
        g2d.drawString(leftScore, leftScoreX, y);
        g2d.drawString("-", dashX, y);
        g2d.drawString(rightScore, rightScoreX, y);
        
        // Calculate available space for names using the ACTUAL drawn score
        // extents (not the fixed-template scoreColStartX/EndX) as the safe
        // edge - see the matching comment in drawHallVictoryEntry above.
        int scoreLeftEdge = Math.min(scoreColStartX, leftScoreX);
        int scoreRightEdge = Math.max(scoreColEndX, rightScoreX + fm.stringWidth(rightScore));
        int playerNameSpace = scoreLeftEdge - 20 - leftX;
        int oppNameSpace = rightX - (scoreRightEdge + 20);

        // Draw player name (right-justified before score column)
        if (playerNameSpace > 20) {
            String displayName = ImageRenderSupport.shortenNameWithInitials(entry.playerName, playerNameSpace, fm);
            int nameWidth = fm.stringWidth(displayName);
            g2d.drawString(displayName, scoreLeftEdge - 20 - nameWidth, y);
        }

        // Draw opponent name (left-justified after score column)
        if (oppNameSpace > 20) {
            String displayName = ImageRenderSupport.shortenNameWithInitials(entry.oppName, oppNameSpace, fm);
            g2d.drawString(displayName, scoreRightEdge + 20, y);
        }
    }
    
    // Name shortening/truncation lives in ImageRenderSupport - previously a
    // byte-identical twin of InfoImageGenerator's copy.

    /**
     * Calculates maximum width needed for data
     */
    private static int calculateMaxWidth(ComparisonData data, FontMetrics fm) {
        int maxWidth = 600;

        // Assumed name budget for this canvas pre-sizing pass ONLY - not the
        // real per-row draw-time constraint (drawHallVictoryEntry/
        // drawPlayerVictoryEntry compute each row's true available space
        // from actual layout geometry instead). Keeps the size estimate in
        // roughly the same ballpark as before shortenNameWithInitials became
        // pixel-aware, when every name was shortened toward ~20 characters
        // for this estimate.
        int nameSizingEstimateWidth = fm.stringWidth("M".repeat(20));

        Graphics2D tempG2d = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
        tempG2d.setFont(HEADER_FONT);
        FontMetrics headerFm = tempG2d.getFontMetrics();
        maxWidth = Math.max(maxWidth, headerFm.stringWidth(data.entityName) + PADDING * 2);
        tempG2d.dispose();
        
        for (Section section : data.sections) {
            if (section.playerVictoryEntries != null) {
                // Calculate width for player victory entries
                for (PlayerVictoryEntry entry : section.playerVictoryEntries) {
                    if (!entry.isNA) {
                        // Estimate width: round + emoji + hall + elo + name + score + name + elo + hall + emoji + spacing
                        // Uses the entry's actual round label - "Round 9" is
                        // wider than "T16 " - so the canvas is sized to fit
                        // what will really be drawn.
                        int roundWidth = fm.stringWidth(entry.round != null && !entry.round.isEmpty() ? entry.round + " " : "T16 ");
                        int emojiWidth = fm.stringWidth(entry.hallEmoji) + 6;
                        int hallWidth = fm.stringWidth(entry.playerHall) + 6;
                        int eloWidth = fm.stringWidth(entry.playerElo) + 20;
                        int nameWidth = fm.stringWidth(ImageRenderSupport.shortenNameWithInitials(entry.playerName, nameSizingEstimateWidth, fm)) + 20;
                        int scoreWidth = fm.stringWidth(entry.score) + 40;
                        int oppNameWidth = fm.stringWidth(ImageRenderSupport.shortenNameWithInitials(entry.oppName, nameSizingEstimateWidth, fm)) + 20;
                        int oppEloWidth = fm.stringWidth(entry.oppElo) + 6;
                        int oppHallWidth = fm.stringWidth(String.format("%3s", entry.oppHall)) + 6;
                        int oppEmojiWidth = fm.stringWidth(entry.oppEmoji);
                        
                        int entryWidth = roundWidth + emojiWidth + hallWidth + eloWidth + nameWidth + 
                                       scoreWidth + oppNameWidth + oppEloWidth + oppHallWidth + oppEmojiWidth + 60;
                        maxWidth = Math.max(maxWidth, entryWidth);
                    }
                }
            } else if (section.hallVictoryEntries != null) {
                // Calculate width for hall victory entries
                for (HallVictoryEntry entry : section.hallVictoryEntries) {
                    if (!entry.isNA) {
                        // Similar calculation for hall entries
                        int roundWidth = fm.stringWidth(entry.round != null && !entry.round.isEmpty() ? entry.round + " " : "T16 ");
                        int emojiWidth = fm.stringWidth(entry.hallEmoji) + 6;
                        int eloWidth = fm.stringWidth(entry.hallElo) + 20;
                        int nameWidth = fm.stringWidth(ImageRenderSupport.shortenNameWithInitials(entry.hallName, nameSizingEstimateWidth, fm)) + 20;
                        int scoreWidth = fm.stringWidth(entry.score) + 40;
                        int oppNameWidth = fm.stringWidth(ImageRenderSupport.shortenNameWithInitials(entry.oppName, nameSizingEstimateWidth, fm)) + 20;
                        int oppEloWidth = fm.stringWidth(entry.oppElo) + 6;
                        int oppEmojiWidth = fm.stringWidth(entry.oppEmoji);
                        
                        int entryWidth = roundWidth + emojiWidth + eloWidth + nameWidth + 
                                       scoreWidth + oppNameWidth + oppEloWidth + oppEmojiWidth + 60;
                        maxWidth = Math.max(maxWidth, entryWidth);
                    }
                }
            } else if (section.lines != null) {
                // Legacy string-based sections
                for (String line : section.lines) {
                    int lineWidth = fm.stringWidth(line) + 40;
                    maxWidth = Math.max(maxWidth, lineWidth);
                }
            }
        }
        
        return maxWidth;
    }

    /**
     * Calculates content height (without icons which are in header)
     */
    private static int calculateContentHeight(ComparisonData data, FontMetrics fm) {
        int height = 0;
        
        Graphics2D tempG2d = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
        tempG2d.setFont(HEADER_FONT);
        FontMetrics headerFm = tempG2d.getFontMetrics();
        tempG2d.dispose(); // FontMetrics stays valid after dispose

        for (Section section : data.sections) {
            // Must match drawSide's actual advance exactly (headerFm.getHeight() + 10,
            // not + 5) - otherwise this budget undercounts by 5px per section and the
            // canvas is sized too short once enough sections are stacked.
            height += headerFm.getHeight() + 10;
            
            // Get size based on section type
            int sectionSize = 0;
            if (section.hallVictoryEntries != null) {
                sectionSize = section.hallVictoryEntries.size();
            } else if (section.playerVictoryEntries != null) {
                sectionSize = section.playerVictoryEntries.size();
            } else if (section.lines != null) {
                sectionSize = section.lines.size();
            }
            
            height += sectionSize * ROW_HEIGHT;
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
        return HallIconLoader.loadRawIcon(hallName);
    }
    
}
