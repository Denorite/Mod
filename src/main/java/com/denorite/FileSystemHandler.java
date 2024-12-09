package com.denorite;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;


public class FileSystemHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("Denorite-FileSystem");
    private static MinecraftServer server;
    private static String worldName;

    public static void initialize(MinecraftServer minecraftServer) {
        server = minecraftServer;
        loadWorldName();
    }

    private static void loadWorldName() {
        try {
            File serverProperties = new File("server.properties");
            if (serverProperties.exists()) {
                Properties props = new Properties();
                try (FileInputStream in = new FileInputStream(serverProperties)) {
                    props.load(in);
                }
                worldName = props.getProperty("level-name", "world");
            } else {
                worldName = "world";
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load server.properties, defaulting to 'world'", e);
            worldName = "world";
        }
    }

    public static JsonObject handleFileCommand(String subcommand, JsonObject args) {
        JsonObject response = new JsonObject();
        try {
            response = switch (subcommand) {
                case "getGameDir" -> getGameDirectory();
                case "list" -> listFiles(args.get("path").getAsString());
                case "download" -> downloadFile(
                        args.get("url").getAsString(),
                        args.get("targetPath").getAsString(),
                        args.has("unzip") && args.get("unzip").getAsBoolean()
                );
                case "delete" -> deleteFile(args.get("path").getAsString());
                case "move" -> moveFile(
                        args.get("source").getAsString(),
                        args.get("destination").getAsString()
                );
                default -> throw new IllegalArgumentException("Unknown subcommand: " + subcommand);
            };
            response.addProperty("success", true);
        } catch (Exception e) {
            LOGGER.error("Error handling file command: " + e.getMessage());
            response.addProperty("success", false);
            response.addProperty("error", e.getMessage());
        }
        return response;
    }

    private static JsonObject getGameDirectory() {
        JsonObject response = new JsonObject();
        response.addProperty("gameDir", server.getRunDirectory().toString());
        return response;
    }

    private static JsonObject listFiles(String pathStr) throws IOException {
        File path = new File(pathStr);
        JsonObject response = new JsonObject();
        JsonArray files = new JsonArray();

        File[] entries = path.listFiles();
        if (entries != null) {
            for (File entry : entries) {
                JsonObject file = new JsonObject();
                file.addProperty("name", entry.getName());
                file.addProperty("isDirectory", entry.isDirectory());
                file.addProperty("size", entry.length());
                file.addProperty("lastModified", entry.lastModified());
                files.add(file);
            }
        }

        response.add("files", files);
        return response;
    }

    private static JsonObject downloadFile(String urlStr, String targetPathStr, boolean unzip) throws IOException {
        File targetPath = new File(targetPathStr);
        JsonObject response = new JsonObject();

        // Ensure parent directories exist
        targetPath.getParentFile().mkdirs();

        URL url = new URL(urlStr);
        try (InputStream in = url.openStream();
             FileOutputStream out = new FileOutputStream(targetPath)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }

            // Only extract if explicitly requested and it's a zip file
            if (unzip && targetPathStr.endsWith(".zip")) {
                extractZip(targetPath);
                targetPath.delete(); // Delete the zip file after extraction
                response.addProperty("extracted", true);
            }
        }

        response.addProperty("path", targetPath.toString());
        return response;
    }

    private static void extractZip(File zipPath) throws IOException {
        File targetDir = zipPath.getParentFile();
        byte[] buffer = new byte[8192];

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipPath))) {
            ZipEntry zipEntry = zis.getNextEntry();
            while (zipEntry != null) {
                File newFile = new File(targetDir, zipEntry.getName());

                if (zipEntry.isDirectory()) {
                    newFile.mkdirs();
                } else {
                    newFile.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(newFile)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zipEntry = zis.getNextEntry();
            }
            zis.closeEntry();
        }
    }

    private static JsonObject deleteFile(String pathStr) throws IOException {
        File path = new File(pathStr);
        JsonObject response = new JsonObject();

        if (path.isDirectory()) {
            deleteDirectory(path);
        } else {
            path.delete();
        }

        response.addProperty("path", path.toString());
        return response;
    }

    private static void deleteDirectory(File directory) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        directory.delete();
    }

    private static JsonObject moveFile(String sourcePath, String destPath) throws IOException {
        File source = new File(sourcePath);
        File dest = new File(destPath);
        JsonObject response = new JsonObject();

        dest.getParentFile().mkdirs();
        if (source.renameTo(dest)) {
            response.addProperty("source", source.toString());
            response.addProperty("destination", dest.toString());
        } else {
            throw new IOException("Failed to move file from " + source + " to " + dest);
        }

        return response;
    }
}
