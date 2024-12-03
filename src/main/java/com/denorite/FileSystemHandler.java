package com.denorite;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class FileSystemHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("Denorite-FileSystem");
    private static final String[] ALLOWED_DIRECTORIES = {
            "mods",
            "datapacks",
            "config",
            "resourcepacks",
            "saves"
    };

    public static void initialize() {
        // registerFileCommands();
        createRequiredDirectories();
    }

    private static void createRequiredDirectories() {
        for (String dir : ALLOWED_DIRECTORIES) {
            try {
                Files.createDirectories(Paths.get(dir));
                LOGGER.info("Ensured directory exists: " + dir);
            } catch (IOException e) {
                LOGGER.error("Failed to create directory: " + dir, e);
            }
        }
    }

    private static void registerFileCommands() {
        JsonObject fileCommand = new JsonObject();
        fileCommand.addProperty("name", "files");

        JsonArray subcommands = new JsonArray();

        // List files command
        JsonObject listFiles = new JsonObject();
        listFiles.addProperty("name", "list");
        JsonArray listArgs = new JsonArray();
        JsonObject pathArg = new JsonObject();
        pathArg.addProperty("name", "path");
        pathArg.addProperty("type", "string");
        listArgs.add(pathArg);
        listFiles.add("arguments", listArgs);
        subcommands.add(listFiles);

        // Download file command
        JsonObject downloadFile = new JsonObject();
        downloadFile.addProperty("name", "download");
        JsonArray downloadArgs = new JsonArray();
        JsonObject urlArg = new JsonObject();
        urlArg.addProperty("name", "url");
        urlArg.addProperty("type", "string");
        downloadArgs.add(urlArg);
        JsonObject targetPathArg = new JsonObject();
        targetPathArg.addProperty("name", "targetPath");
        targetPathArg.addProperty("type", "string");
        downloadArgs.add(targetPathArg);
        downloadFile.add("arguments", downloadArgs);
        subcommands.add(downloadFile);

        // Delete file command
        JsonObject deleteFile = new JsonObject();
        deleteFile.addProperty("name", "delete");
        JsonArray deleteArgs = new JsonArray();
        JsonObject filePathArg = new JsonObject();
        filePathArg.addProperty("name", "path");
        filePathArg.addProperty("type", "string");
        deleteArgs.add(filePathArg);
        deleteFile.add("arguments", deleteArgs);
        subcommands.add(deleteFile);

        // Move/rename file command
        JsonObject moveFile = new JsonObject();
        moveFile.addProperty("name", "move");
        JsonArray moveArgs = new JsonArray();
        JsonObject sourceArg = new JsonObject();
        sourceArg.addProperty("name", "source");
        sourceArg.addProperty("type", "string");
        moveArgs.add(sourceArg);
        JsonObject destArg = new JsonObject();
        destArg.addProperty("name", "destination");
        destArg.addProperty("type", "string");
        moveArgs.add(destArg);
        moveFile.add("arguments", moveArgs);
        subcommands.add(moveFile);

        fileCommand.add("subcommands", subcommands);
        DynamicCommandHandler.registerCommand(fileCommand);
    }

    public static JsonObject handleFileCommand(String subcommand, JsonObject args) {
        JsonObject response = new JsonObject();
        try {
            switch (subcommand) {
                case "list" -> response = listFiles(args.get("path").getAsString());
                case "download" -> response = downloadFile(
                        args.get("url").getAsString(),
                        args.get("targetPath").getAsString()
                );
                case "delete" -> response = deleteFile(args.get("path").getAsString());
                case "move" -> response = moveFile(
                        args.get("source").getAsString(),
                        args.get("destination").getAsString()
                );
                default -> throw new IllegalArgumentException("Unknown subcommand: " + subcommand);
            }
            response.addProperty("success", true);
        } catch (Exception e) {
            LOGGER.error("Error handling file command: " + e.getMessage());
            response.addProperty("success", false);
            response.addProperty("error", e.getMessage());
        }
        return response;
    }

    private static boolean isPathAllowed(String path) {
        Path normalizedPath = Paths.get(path).normalize();
        String pathStr = normalizedPath.toString();

        // Check if path starts with any allowed directory
        for (String allowedDir : ALLOWED_DIRECTORIES) {
            if (pathStr.startsWith(allowedDir)) {
                return true;
            }
        }
        return false;
    }

    private static JsonObject listFiles(String pathStr) throws IOException {
        if (!isPathAllowed(pathStr)) {
            throw new SecurityException("Access to this directory is not allowed");
        }

        Path path = Paths.get(pathStr);
        JsonObject response = new JsonObject();
        JsonArray files = new JsonArray();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
            for (Path entry : stream) {
                JsonObject file = new JsonObject();
                file.addProperty("name", entry.getFileName().toString());
                file.addProperty("isDirectory", Files.isDirectory(entry));
                file.addProperty("size", Files.size(entry));
                file.addProperty("lastModified", Files.getLastModifiedTime(entry).toMillis());
                files.add(file);
            }
        }

        response.add("files", files);
        return response;
    }

    private static JsonObject downloadFile(String urlStr, String targetPathStr) throws IOException {
        if (!isPathAllowed(targetPathStr)) {
            throw new SecurityException("Access to this directory is not allowed");
        }

        Path targetPath = Paths.get(targetPathStr);
        JsonObject response = new JsonObject();

        URL url = new URL(urlStr);
        try (InputStream in = url.openStream()) {
            Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);

            // If it's a zip file, try to extract it
            if (targetPathStr.endsWith(".zip")) {
                extractZip(targetPath);
                Files.delete(targetPath); // Delete the zip file after extraction
                response.addProperty("extracted", true);
            }
        }

        response.addProperty("path", targetPath.toString());
        return response;
    }

    private static void extractZip(Path zipPath) throws IOException {
        Path targetDir = zipPath.getParent();
        byte[] buffer = new byte[1024];

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipPath.toFile()))) {
            ZipEntry zipEntry = zis.getNextEntry();
            while (zipEntry != null) {
                Path newPath = targetDir.resolve(zipEntry.getName()).normalize();

                // Security check: ensure we're not extracting outside the target directory
                if (!newPath.startsWith(targetDir)) {
                    throw new SecurityException("Zip entry attempting to write outside target directory");
                }

                if (zipEntry.isDirectory()) {
                    Files.createDirectories(newPath);
                } else {
                    Files.createDirectories(newPath.getParent());
                    try (FileOutputStream fos = new FileOutputStream(newPath.toFile())) {
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
        if (!isPathAllowed(pathStr)) {
            throw new SecurityException("Access to this directory is not allowed");
        }

        Path path = Paths.get(pathStr);
        JsonObject response = new JsonObject();

        if (Files.isDirectory(path)) {
            Files.walk(path)
                    .sorted((p1, p2) -> -p1.compareTo(p2)) // Reverse order to delete contents first
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            LOGGER.error("Error deleting path: " + p, e);
                        }
                    });
        } else {
            Files.delete(path);
        }

        response.addProperty("path", path.toString());
        return response;
    }

    private static JsonObject moveFile(String sourcePath, String destPath) throws IOException {
        if (!isPathAllowed(sourcePath) || !isPathAllowed(destPath)) {
            throw new SecurityException("Access to this directory is not allowed");
        }

        Path source = Paths.get(sourcePath);
        Path dest = Paths.get(destPath);
        JsonObject response = new JsonObject();

        Files.move(source, dest, StandardCopyOption.REPLACE_EXISTING);

        response.addProperty("source", source.toString());
        response.addProperty("destination", dest.toString());
        return response;
    }
}
