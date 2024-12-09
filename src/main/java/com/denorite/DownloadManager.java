package com.denorite;

import com.google.gson.JsonObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

public class DownloadManager {
    private static final Map<String, CompletableFuture<JsonObject>> activeDownloads = new ConcurrentHashMap<>();

    public static void trackDownload(String downloadId, CompletableFuture<JsonObject> future) {
        activeDownloads.put(downloadId, future);

        future.whenComplete((result, error) -> {
            activeDownloads.remove(downloadId);

            JsonObject response = new JsonObject();
            response.addProperty("type", "download_completed");
            response.addProperty("downloadId", downloadId);
            if (error != null) {
                response.addProperty("success", false);
                response.addProperty("error", error.getMessage());
            } else {
                response.addProperty("success", true);
                response.add("result", result);
            }

            Denorite.sendToTypeScript("file_download_completed", response);
        });
    }
}
