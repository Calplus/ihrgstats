package com.calplus.ihrgstats.utils;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * Utility class for generating table images with colored rows
 */
public class TableImageGenerator {
    
    private static final int ROW_HEIGHT = 30;
    private static final int ICON_SIZE = 30;
    private static final int PADDING = 0;  // Padding around table content
    
    // Color scheme 1: Light blue and lighter blue
    private static final Color BLUE_LIGHT = new Color(173, 216, 230);  // LightBlue
    private static final Color BLUE_LIGHTER = new Color(224, 255, 255);  // LightCyan
    
    // Color scheme 2: Light red and lighter red  
    private static final Color RED_LIGHT = new Color(255, 182, 193);  // LightPink
    private static final Color RED_LIGHTER = new Color(255, 228, 225);  // MistyRose
    
    private static final Color TEXT_COLOR = Color.BLACK;
    private static final Font TABLE_FONT = FontManager.getMonoFont(24);
    private static final Font TITLE_FONT = FontManager.getSansBoldFont(48);
    private static final Font METADATA_FONT = FontManager.getSansFont(24);
    
    /**
     * Image metadata for headers
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
     * Crops an image to its actual content bounds
     * @param original Original image
     * @param contentX X position of content start
     * @param contentY Y position of content start
     * @param contentWidth Width of content
     * @param contentHeight Height of content
     * @return Cropped image
     */
    private static BufferedImage cropImage(BufferedImage original, int contentX, int contentY, 
                                          int contentWidth, int contentHeight) {
        // Add small padding around content
        int cropPadding = 10;
        
        int x = Math.max(0, contentX - cropPadding);
        int y = Math.max(0, contentY - cropPadding);
        int width = Math.min(original.getWidth() - x, contentWidth + 2 * cropPadding);
        int height = Math.min(original.getHeight() - y, contentHeight + 2 * cropPadding);
        
        // Create cropped image
        return original.getSubimage(x, y, width, height);
    }
    
    /**
     * Calculates actual pixel widths for each column based on content
     * @param headers Column headers
     * @param rows Data rows
     * @param fm FontMetrics for measuring text
     * @return Array of column widths in pixels
     */
    private static int[] calculateColumnWidths(String[] headers, List<String[]> rows, FontMetrics fm) {
        int[] widths = new int[headers.length];
        
        // Start with header widths
        for (int i = 0; i < headers.length; i++) {
            widths[i] = fm.stringWidth(headers[i]);
        }
        
        // Check all data rows and use maximum width
        for (String[] row : rows) {
            for (int i = 0; i < row.length && i < widths.length; i++) {
                int cellWidth = fm.stringWidth(row[i]);
                if (cellWidth > widths[i]) {
                    widths[i] = cellWidth;
                }
            }
        }
        
        // Add padding to each column
        int cellPadding = 20; // Padding on each side of cell content
        for (int i = 0; i < widths.length; i++) {
            widths[i] += cellPadding;
        }
        
        return widths;
    }
    
    /**
     * Calculates total table width including all columns and spacing
     * @param columnWidths Array of column widths in pixels
     * @param fm FontMetrics for measuring spacing
     * @return Total table width in pixels
     */
    private static int calculateTableWidth(int[] columnWidths, FontMetrics fm) {
        int totalWidth = 0;
        int columnSpacing = fm.charWidth('M'); // Space between columns
        
        for (int i = 0; i < columnWidths.length; i++) {
            totalWidth += columnWidths[i];
            if (i < columnWidths.length - 1) {
                totalWidth += columnSpacing; // Add spacing between columns
            }
        }
        
        return totalWidth + 10; // Add extra padding on edges
    }
    
    /**
     * Calculates required header height based on metadata content and font sizes
     * @param metadata Image metadata (can be null)
     * @return Required header height in pixels, or 0 if no metadata
     */
    private static int calculateHeaderHeight(ImageMetadata metadata) {
        if (metadata == null) {
            return 0;
        }
        
        // Create temporary graphics to get font metrics
        BufferedImage tempImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D tempG2d = tempImage.createGraphics();
        
        // Get font metrics
        tempG2d.setFont(TITLE_FONT);
        FontMetrics titleFm = tempG2d.getFontMetrics();
        tempG2d.setFont(METADATA_FONT);
        FontMetrics metadataFm = tempG2d.getFontMetrics();
        
        tempG2d.dispose();
        
        // Calculate total height
        int topPadding = Math.max(20, titleFm.getHeight() / 2); // Top margin
        int height = topPadding;
        
        // Title line
        height += titleFm.getHeight();
        
        // Spacing after title (proportional to font size)
        height += metadataFm.getHeight() / 2;
        
        // Description lines (split by \n)
        if (metadata.description != null && !metadata.description.isEmpty()) {
            String[] descriptionLines = metadata.description.split("\n");
            height += metadataFm.getHeight() * descriptionLines.length;
        }
        
        // Spacing after description
        height += metadataFm.getHeight() / 3;
        
        // Last round line (if provided)
        if (metadata.lastRound != null && !metadata.lastRound.isEmpty()) {
            height += metadataFm.getHeight();
            height += metadataFm.getHeight() / 3;
        }
        
        // Generated date/time line
        height += metadataFm.getHeight();
        
        // Bottom padding
        height += Math.max(20, metadataFm.getHeight() / 2);

        return height;
    }

    /**
     * Computes the pixel width of the widest line {@code drawHeaderMetadata}
     * actually draws (title, each description line, "Last Round: X",
     * "Generated: ..."). The title/description used to be centered on the
     * full image width while the final image got cropped down to just the
     * table's own (possibly narrower) width, clipping the header text at
     * both edges for a narrow table (A37) - both the canvas width and the
     * final crop bounds now account for this too, not just the table.
     * @param metadata Image metadata (can be null)
     * @return Required width in pixels, or 0 if no metadata
     */
    static int calculateHeaderContentWidth(ImageMetadata metadata) {
        if (metadata == null) {
            return 0;
        }

        BufferedImage tempImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D tempG2d = tempImage.createGraphics();

        tempG2d.setFont(TITLE_FONT);
        FontMetrics titleFm = tempG2d.getFontMetrics();
        tempG2d.setFont(METADATA_FONT);
        FontMetrics metadataFm = tempG2d.getFontMetrics();

        tempG2d.dispose();

        int maxWidth = titleFm.stringWidth(metadata.title);

        if (metadata.description != null && !metadata.description.isEmpty()) {
            for (String line : metadata.description.split("\n")) {
                maxWidth = Math.max(maxWidth, metadataFm.stringWidth(line));
            }
        }

        if (metadata.lastRound != null && !metadata.lastRound.isEmpty()) {
            maxWidth = Math.max(maxWidth, metadataFm.stringWidth("Last Round: " + metadata.lastRound));
        }

        maxWidth = Math.max(maxWidth, metadataFm.stringWidth("Generated: " + TimezoneHelper.formatNow("yyyy-MM-dd HH:mm:ss")));

        return maxWidth;
    }

    private static final Color GREEN_HIGHLIGHT = new Color(144, 238, 144);  // LightGreen for home hall
    
    /**
     * Generates a table image for player rankings
     * @param headers Column headers
     * @param rows Data rows
     * @param alignments Column alignments
     * @param columnWidths Column widths in characters
     * @param metadata Image title and metadata (can be null)
     * @return Path to generated image
     */
    public static Path generatePlayerTable(String[] headers, List<String[]> rows,
                                          TableFormatter.Alignment[] alignments,
                                          int[] columnWidths,
                                          ImageMetadata metadata) throws IOException {
        return generatePlayerTable(headers, rows, alignments, columnWidths, metadata, null, "RankPlayers", "");
    }

    public static Path generatePlayerTable(String[] headers, List<String[]> rows,
                                          TableFormatter.Alignment[] alignments,
                                          int[] columnWidths,
                                          ImageMetadata metadata,
                                          Set<Integer> highlightRows,
                                          String commandName,
                                          String entityName) throws IOException {
        // Calculate optimal table width based on actual content
        FontMetrics fm = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
            .createGraphics().getFontMetrics(TABLE_FONT);
        
        // Calculate actual pixel widths for each column based on content
        int[] actualColumnWidths = calculateColumnWidths(headers, rows, fm);
        
        // Calculate total table width including spacing
        int tableWidth = calculateTableWidth(actualColumnWidths, fm);
        // The canvas must be wide enough for the header text too, not just
        // the table - otherwise a short table with a long title/description
        // gets clipped, either off-canvas or by the crop below (A37).
        int headerContentWidth = calculateHeaderContentWidth(metadata);
        int imageWidth = Math.max(Math.max(tableWidth, headerContentWidth) + 40, 600);

        // Calculate dynamic header height based on metadata
        int headerOffset = calculateHeaderHeight(metadata);
        int totalRows = rows.size() + 1; // +1 for header
        int imageHeight = headerOffset + totalRows * ROW_HEIGHT + PADDING * 2;

        BufferedImage image = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();

        // Enable anti-aliasing
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Fill background
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, imageWidth, imageHeight);

        // Draw header metadata if provided
        if (metadata != null) {
            drawHeaderMetadata(g2d, metadata, imageWidth);
        }

        // Calculate table starting position (centered)
        int tableStartX = (imageWidth - tableWidth) / 2;
        int tableStartY = headerOffset + PADDING;
        
        g2d.setFont(TABLE_FONT);
        
        // Draw header row (light gray background)
        g2d.setColor(new Color(220, 220, 220));
        g2d.fillRect(tableStartX, tableStartY, tableWidth, ROW_HEIGHT);
        g2d.setColor(TEXT_COLOR);
        drawRow(g2d, headers, 0, alignments, actualColumnWidths, tableStartX, tableStartY);
        
        // Draw data rows
        for (int i = 0; i < rows.size(); i++) {
            int rowIndex = i + 1;
            int yPos = tableStartY + rowIndex * ROW_HEIGHT;
            
            // Determine color based on position or highlight
            Color bgColor;
            if (highlightRows != null && highlightRows.contains(i)) {
                bgColor = GREEN_HIGHLIGHT;  // Green for highlighted rows (home hall)
            } else {
                bgColor = getRowColor(i);
            }
            
            g2d.setColor(bgColor);
            g2d.fillRect(tableStartX, yPos, tableWidth, ROW_HEIGHT);
            
            g2d.setColor(TEXT_COLOR);
            drawRow(g2d, rows.get(i), rowIndex, alignments, actualColumnWidths, tableStartX, tableStartY);
        }
        
        g2d.dispose();

        // Crop image to content bounds - wide enough for the header text as
        // well as the table, so a short table with a long title/description
        // doesn't get its header clipped at both edges (A37).
        int croppedContentWidth = Math.min(imageWidth, Math.max(tableWidth, headerContentWidth));
        int contentX = (imageWidth - croppedContentWidth) / 2;
        int contentY = 0;  // Include header from top
        int contentWidth = croppedContentWidth;
        int contentHeight = headerOffset + totalRows * ROW_HEIGHT + PADDING * 2;
        BufferedImage croppedImage = cropImage(image, contentX, contentY, contentWidth, contentHeight);

        // Generate filename with convention: {command}_{name}_{date}_{time}.png
        String timestamp = TimezoneHelper.formatNow("yyMMdd_HHmmss");
        String sanitizedName = entityName.isEmpty() ? "" : sanitizeName(entityName) + "_";
        String filename = String.format("%s_%s%s.png", commandName, sanitizedName, timestamp);

        // Save to the shared, dedicated output directory (not the OS temp
        // dir - nothing was ever cleaning that up, so images accumulated
        // there indefinitely with no inspectable, intentional home).
        Path tempFile = OutputPaths.getOutputDirectory().resolve(filename);
        ImageIO.write(croppedImage, "PNG", tempFile.toFile());

        return tempFile;
    }

    /**
     * Generates a table image for hall rankings with hall icons
     * @param headers Column headers
     * @param rows Data rows
     * @param hallNames Hall names for each row
     * @param alignments Column alignments
     * @param columnWidths Column widths in characters
     * @return Path to generated image
     */
    public static Path generateHallTable(String[] headers, List<String[]> rows,
                                        List<String> hallNames,
                                        TableFormatter.Alignment[] alignments,
                                        int[] columnWidths,
                                        ImageMetadata metadata) throws IOException {
        return generateHallTable(headers, rows, hallNames, alignments, columnWidths, metadata, null, "RankHalls", "");
    }

    public static Path generateHallTable(String[] headers, List<String[]> rows,
                                        List<String> hallNames,
                                        TableFormatter.Alignment[] alignments,
                                        int[] columnWidths,
                                        ImageMetadata metadata,
                                        Set<Integer> highlightRows,
                                        String commandName,
                                        String entityName) throws IOException {
        // Calculate optimal table width based on actual content
        FontMetrics fm = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
            .createGraphics().getFontMetrics(TABLE_FONT);
        
        // Calculate actual pixel widths for each column based on content
        int[] actualColumnWidths = calculateColumnWidths(headers, rows, fm);
        
        // Calculate total table width including spacing and icon
        int tableWidth = calculateTableWidth(actualColumnWidths, fm) + ICON_SIZE;
        // The canvas must be wide enough for the header text too, not just
        // the table - otherwise a short table with a long title/description
        // gets clipped, either off-canvas or by the crop below (A37).
        int headerContentWidth = calculateHeaderContentWidth(metadata);
        int imageWidth = Math.max(Math.max(tableWidth, headerContentWidth) + 40, 600);
        
        // Calculate dynamic header height based on metadata
        int headerOffset = calculateHeaderHeight(metadata);
        int totalRows = rows.size() + 1; // +1 for header
        int imageHeight = headerOffset + totalRows * ROW_HEIGHT + PADDING * 2;
        
        BufferedImage image = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();
        
        // Enable anti-aliasing
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        
        // Fill background
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, imageWidth, imageHeight);
        
        // Draw header metadata if provided
        if (metadata != null) {
            drawHeaderMetadata(g2d, metadata, imageWidth);
        }
        
        // Calculate table starting position (centered)
        int tableStartX = (imageWidth - tableWidth) / 2;
        int tableStartY = headerOffset + PADDING;
        
        g2d.setFont(TABLE_FONT);
        
        // Draw header row (light gray background)
        g2d.setColor(new Color(220, 220, 220));
        g2d.fillRect(tableStartX, tableStartY, tableWidth, ROW_HEIGHT);
        g2d.setColor(TEXT_COLOR);
        drawRow(g2d, headers, 0, alignments, actualColumnWidths, tableStartX + ICON_SIZE, tableStartY);
        
        // Draw data rows with hall icons
        for (int i = 0; i < rows.size(); i++) {
            int rowIndex = i + 1;
            int yPos = tableStartY + rowIndex * ROW_HEIGHT;
            
            // Determine color based on position or highlight
            Color bgColor;
            if (highlightRows != null && highlightRows.contains(i)) {
                bgColor = GREEN_HIGHLIGHT;  // Green for highlighted rows (home hall)
            } else {
                bgColor = getRowColor(i);
            }
            
            g2d.setColor(bgColor);
            g2d.fillRect(tableStartX, yPos, tableWidth, ROW_HEIGHT);
            
            // Draw hall icon
            String hallName = hallNames.get(i);
            BufferedImage hallIcon = loadHallIcon(hallName);
            if (hallIcon != null) {
                g2d.drawImage(hallIcon, tableStartX, yPos, ICON_SIZE, ICON_SIZE, null);
            }
            
            g2d.setColor(TEXT_COLOR);
            drawRow(g2d, rows.get(i), rowIndex, alignments, actualColumnWidths, tableStartX + ICON_SIZE, tableStartY);
        }
        
        g2d.dispose();

        // Crop image to content bounds - wide enough for the header text as
        // well as the table, so a short table with a long title/description
        // doesn't get its header clipped at both edges (A37).
        int croppedContentWidth = Math.min(imageWidth, Math.max(tableWidth, headerContentWidth));
        int contentX = (imageWidth - croppedContentWidth) / 2;
        int contentY = 0;  // Include header from top
        int contentWidth = croppedContentWidth;
        int contentHeight = headerOffset + totalRows * ROW_HEIGHT + PADDING * 2;
        BufferedImage croppedImage = cropImage(image, contentX, contentY, contentWidth, contentHeight);

        // Generate filename with convention: {command}_{name}_{date}_{time}.png
        String timestamp = TimezoneHelper.formatNow("yyMMdd_HHmmss");
        String sanitizedName = entityName.isEmpty() ? "" : sanitizeName(entityName) + "_";
        String filename = String.format("%s_%s%s.png", commandName, sanitizedName, timestamp);
        
        // Save to the shared, dedicated output directory (not the OS temp
        // dir - nothing was ever cleaning that up, so images accumulated
        // there indefinitely with no inspectable, intentional home).
        Path tempFile = OutputPaths.getOutputDirectory().resolve(filename);
        ImageIO.write(croppedImage, "PNG", tempFile.toFile());
        
        return tempFile;
    }
    
    /**
     * Draws header metadata (title, description, date, etc.)
     */
    private static void drawHeaderMetadata(Graphics2D g2d, ImageMetadata metadata, int imageWidth) {
        int centerX = imageWidth / 2;
        
        // Get font metrics for dynamic spacing
        g2d.setFont(TITLE_FONT);
        FontMetrics titleFm = g2d.getFontMetrics();
        g2d.setFont(METADATA_FONT);
        FontMetrics metadataFm = g2d.getFontMetrics();
        
        // Start position with dynamic top padding
        int y = Math.max(20, titleFm.getHeight() / 2) + titleFm.getAscent();
        
        // Draw title
        g2d.setFont(TITLE_FONT);
        g2d.setColor(TEXT_COLOR);
        int titleWidth = titleFm.stringWidth(metadata.title);
        g2d.drawString(metadata.title, centerX - titleWidth / 2, y);
        
        // Dynamic spacing after title (proportional to font size)
        y += metadataFm.getHeight() / 2 + metadataFm.getAscent();
        
        // Draw description (split by \n for multi-line support)
        g2d.setFont(METADATA_FONT);
        if (metadata.description != null && !metadata.description.isEmpty()) {
            String[] descriptionLines = metadata.description.split("\n");
            for (String line : descriptionLines) {
                int descWidth = metadataFm.stringWidth(line);
                g2d.drawString(line, centerX - descWidth / 2, y);
                y += metadataFm.getHeight();
            }
        }
        
        // Dynamic spacing after description
        y += metadataFm.getHeight() / 3;
        
        // Draw last round if provided
        if (metadata.lastRound != null && !metadata.lastRound.isEmpty()) {
            String lastRoundText = "Last Round: " + metadata.lastRound;
            int lastRoundWidth = metadataFm.stringWidth(lastRoundText);
            g2d.drawString(lastRoundText, centerX - lastRoundWidth / 2, y);
            y += metadataFm.getHeight() / 3 + metadataFm.getAscent();
        }
        
        // Draw export date/time
        String dateTime = "Generated: " + TimezoneHelper.formatNow("yyyy-MM-dd HH:mm:ss");
        int dateWidth = metadataFm.stringWidth(dateTime);
        g2d.drawString(dateTime, centerX - dateWidth / 2, y);
    }
    
    /**
     * Gets the background color for a row based on its index
     */
    private static Color getRowColor(int rowIndex) {
        // Every 10 rows, switch color scheme
        int schemeGroup = (rowIndex / 10) % 2;
        
        // Within each group, alternate between light and lighter
        boolean isEvenRow = rowIndex % 2 == 0;
        
        if (schemeGroup == 0) {
            // Blue scheme
            return isEvenRow ? BLUE_LIGHT : BLUE_LIGHTER;
        } else {
            // Red scheme
            return isEvenRow ? RED_LIGHT : RED_LIGHTER;
        }
    }
    
    /**
     * Draws a single row of data
     */
    private static void drawRow(Graphics2D g2d, String[] cells, int rowIndex,
                                TableFormatter.Alignment[] alignments,
                                int[] columnWidths, int leftOffset, int topOffset) {
        FontMetrics fm = g2d.getFontMetrics();
        int columnSpacing = fm.charWidth('M'); // Space between columns
        // Center text vertically within the row
        int yPos = topOffset + rowIndex * ROW_HEIGHT + (ROW_HEIGHT - fm.getHeight()) / 2 + fm.getAscent();
        
        int xPos = leftOffset + 5; // Start position with padding
        
        for (int i = 0; i < cells.length; i++) {
            String cell = cells[i];
            int cellWidth = columnWidths[i]; // Now using actual pixel widths
            
            // Calculate x position based on alignment
            int textX;
            int textWidth = fm.stringWidth(cell);
            
            switch (alignments[i]) {
                case LEFT:
                    textX = xPos;
                    break;
                case RIGHT:
                    textX = xPos + cellWidth - textWidth;
                    break;
                case CENTER:
                    textX = xPos + (cellWidth - textWidth) / 2;
                    break;
                default:
                    textX = xPos;
            }
            
            g2d.drawString(cell, textX, yPos);
            xPos += cellWidth;
            if (i < cells.length - 1) {
                xPos += columnSpacing; // Add spacing between columns
            }
        }
    }
    
    /**
     * Loads a hall icon image from resources
     */
    private static BufferedImage loadHallIcon(String hallName) {
        BufferedImage original = HallIconLoader.loadRawIcon(hallName);
        if (original == null) {
            return null;
        }

        // Resize to ICON_SIZE x ICON_SIZE
        BufferedImage resized = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = resized.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.drawImage(original, 0, 0, ICON_SIZE, ICON_SIZE, null);
        g2d.dispose();

        return resized;
    }
    
    /**
     * Sanitizes a name for use in a filename by removing invalid characters
     */
    private static String sanitizeName(String name) {
        if (name == null || name.isEmpty()) return "";
        return name.replaceAll("[^a-zA-Z0-9_-]", "_").replaceAll("_{2,}", "_").trim();
    }
}
