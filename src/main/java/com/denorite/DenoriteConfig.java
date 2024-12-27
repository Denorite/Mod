package com.denorite;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class DenoriteConfig {
    private static final String CONFIG_FILE = "config/denorite.json";
    private String jwtToken;
    private String serverUrl;
    private String mcServerUrl;
    private boolean strictMode;

    public DenoriteConfig() {
        loadConfig();
    }

    private void loadConfig() {
        File configFile = new File(CONFIG_FILE);
        if (!configFile.exists()) {
            createDefaultConfig(configFile);
        }

        try {
            String jsonContent = FileUtils.readFileToString(configFile, StandardCharsets.UTF_8);
            JsonObject config = new Gson().fromJson(jsonContent, JsonObject.class);

            this.jwtToken = config.get("jwtToken").getAsString();
            this.serverUrl = config.get("serverUrl").getAsString();
            this.mcServerUrl = config.get("mcServerUrl").getAsString();
            this.strictMode = config.get("strictMode").getAsBoolean();
        } catch (IOException e) {
            Denorite.LOGGER.error("Error reading config file: " + e.getMessage());
        }
    }

    private void createDefaultConfig(File configFile) {
        JsonObject defaultConfig = new JsonObject();
        defaultConfig.addProperty("jwtToken", "");
        defaultConfig.addProperty("serverUrl", "ws://server:8082");
        defaultConfig.addProperty("mcServerUrl", "mc");
        defaultConfig.addProperty("strictMode", true);

        try {
            FileUtils.write(configFile, new Gson().toJson(defaultConfig), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Denorite.LOGGER.error("Error creating default config file: " + e.getMessage());
        }
    }

    public String getJwtToken() {
        return jwtToken;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public boolean isStrictMode() {
        return strictMode;
    }

    public String getOrigin() {
        return mcServerUrl;
    }
}
