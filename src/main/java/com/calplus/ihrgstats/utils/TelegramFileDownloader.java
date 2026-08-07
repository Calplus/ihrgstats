package com.calplus.ihrgstats.utils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Utility for downloading files from Telegram Bot API.
 * Handles file retrieval and local storage.
 */
public class TelegramFileDownloader {
    private final String botToken;
    private final HttpClient httpClient;

    public TelegramFileDownloader(String botToken) {
        this.botToken = botToken;
        this.httpClient = HttpClientFactory.newClient();
    }

    /**
     * Downloads a file from Telegram using file_id
     * @param fileId The Telegram file_id
     * @param destinationPath Local path to save the file
     * @return true if successful, false otherwise
     */
    public boolean downloadFile(String fileId, String destinationPath) {
        try {
            // Step 1: Get file path from Telegram
            String getFileUrl = "https://api.telegram.org/bot" + botToken + "/getFile?file_id=" + fileId;
            
            HttpRequest getFileRequest = HttpRequest.newBuilder()
                .uri(URI.create(getFileUrl))
                .timeout(HttpClientFactory.REQUEST_TIMEOUT)
                .GET()
                .build();

            HttpResponse<String> getFileResponse = httpClient.send(getFileRequest, HttpResponse.BodyHandlers.ofString());

            if (getFileResponse.statusCode() != 200) {
                System.err.println("Failed to get file info from Telegram: " + getFileResponse.statusCode());
                return false;
            }

            // Parse response to get file_path
            String responseBody = getFileResponse.body();
            String filePath = extractFilePathFromJson(responseBody);
            
            if (filePath == null) {
                System.err.println("Failed to extract file_path from Telegram response");
                return false;
            }

            // Step 2: Download the file
            String downloadUrl = "https://api.telegram.org/file/bot" + botToken + "/" + filePath;
            
            HttpRequest downloadRequest = HttpRequest.newBuilder()
                .uri(URI.create(downloadUrl))
                .timeout(HttpClientFactory.FILE_TRANSFER_TIMEOUT)
                .GET()
                .build();

            HttpResponse<byte[]> downloadResponse = httpClient.send(downloadRequest, HttpResponse.BodyHandlers.ofByteArray());

            if (downloadResponse.statusCode() != 200) {
                System.err.println("Failed to download file from Telegram: " + downloadResponse.statusCode());
                return false;
            }

            // Step 3: Save to local file
            Path destPath = Paths.get(destinationPath);
            Files.createDirectories(destPath.getParent());
            Files.write(destPath, downloadResponse.body());

            System.out.println("File downloaded successfully to: " + destinationPath);
            return true;

        } catch (Exception e) {
            System.err.println("Error downloading file from Telegram: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Downloads a file to a temporary location
     * @param fileId The Telegram file_id
     * @param filename The filename to use
     * @return Path to the downloaded file, or null if failed
     */
    public Path downloadToTemp(String fileId, String filename) {
        try {
            // Each download gets its own UUID-named subdirectory rather than
            // sharing one flat temp/ directory keyed only on the original
            // filename - two concurrent uploads of the same filename (e.g. a
            // re-upload of "2025_round_3.csv" moments after the first) would
            // otherwise silently overwrite each other's file mid-processing,
            // and one upload's cleanup (deleteTempFile) could delete the
            // file the OTHER upload is still reading. Every upload now runs
            // on its own thread (since the confirmation-deadlock fix), so
            // this collision is reachable in practice, not just in theory.
            Path tempDir = Paths.get(System.getProperty("user.dir"), "temp", UUID.randomUUID().toString());
            Files.createDirectories(tempDir);

            Path tempFile = tempDir.resolve(filename);

            if (downloadFile(fileId, tempFile.toString())) {
                return tempFile;
            }
            return null;

        } catch (Exception e) {
            System.err.println("Error creating temp directory: " + e.getMessage());
            return null;
        }
    }

    /**
     * Extracts file_path from Telegram getFile JSON response
     * Simple JSON parsing without external library
     */
    private String extractFilePathFromJson(String json) {
        // Look for "file_path":"..."
        String searchStr = "\"file_path\":\"";
        int startIdx = json.indexOf(searchStr);
        
        if (startIdx == -1) {
            return null;
        }
        
        startIdx += searchStr.length();
        int endIdx = json.indexOf("\"", startIdx);
        
        if (endIdx == -1) {
            return null;
        }
        
        return json.substring(startIdx, endIdx);
    }

    /**
     * Deletes a temporary file
     * @param path Path to the file to delete
     */
    public static void deleteTempFile(Path path) {
        try {
            if (path != null && Files.exists(path)) {
                Files.delete(path);
                System.out.println("Temporary file deleted: " + path);
                // Best-effort: remove the per-download UUID subdirectory
                // downloadToTemp() created for this file, now that it's
                // empty, so these don't accumulate indefinitely. Silently
                // ignored if the parent isn't empty or isn't one of ours.
                try {
                    Path parent = path.getParent();
                    if (parent != null) {
                        Files.deleteIfExists(parent);
                    }
                } catch (Exception ignored) {
                    // Non-empty, or some other reason it couldn't be removed - harmless either way.
                }
            }
        } catch (Exception e) {
            System.err.println("Error deleting temporary file: " + e.getMessage());
        }
    }
}
