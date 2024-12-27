package com.denorite;

import com.flowpowered.math.vector.Vector3d;
import com.google.gson.*;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.markers.*;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Line;
import de.bluecolored.bluemap.api.math.Shape;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.api.ModInitializer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlueMapIntegration {

    private static final Logger LOGGER = LoggerFactory.getLogger(
        "Denorite-BlueMap"
    );
    private static final Map<String, MarkerSet> markerSets =
        new ConcurrentHashMap<>();
    private static boolean isEnabled = false;

    public static void initialize() {
        try {
            Class.forName("de.bluecolored.bluemap.api.BlueMapAPI");
            isEnabled = true;
            LOGGER.info("BlueMap API found, enabling integration");

            BlueMapAPI.onEnable(api -> {
                LOGGER.info("BlueMap API enabled, ready to handle markers");
                registerBlueMapCommands();
            });

            BlueMapAPI.onDisable(api -> {
                LOGGER.info("BlueMap API disabled");
                markerSets.clear();
            });
        } catch (ClassNotFoundException e) {
            LOGGER.info("BlueMap API not found, markers will not be available");
        }
    }

    private static void registerBlueMapCommands() {
        if (!isEnabled) return;

        JsonObject markerCommand = new JsonObject();
        markerCommand.addProperty("name", "bluemap");

        JsonArray subcommands = new JsonArray();

        // Create marker set
        JsonObject createSet = new JsonObject();
        createSet.addProperty("name", "createSet");
        JsonArray createSetArgs = new JsonArray();
        addCreateSetArguments(createSetArgs);
        createSet.add("arguments", createSetArgs);
        subcommands.add(createSet);

        // Remove marker set
        JsonObject removeSet = new JsonObject();
        removeSet.addProperty("name", "removeSet");
        JsonArray removeSetArgs = new JsonArray();
        addRemoveSetArguments(removeSetArgs);
        removeSet.add("arguments", removeSetArgs);
        subcommands.add(removeSet);

        // List marker sets
        JsonObject listSets = new JsonObject();
        listSets.addProperty("name", "listSets");
        subcommands.add(listSets);

        // Add marker
        JsonObject addMarker = new JsonObject();
        addMarker.addProperty("name", "add");
        JsonArray addArgs = new JsonArray();
        addMarkerArguments(addArgs);
        addMarker.add("arguments", addArgs);
        subcommands.add(addMarker);

        // Remove marker
        JsonObject removeMarker = new JsonObject();
        removeMarker.addProperty("name", "remove");
        JsonArray removeArgs = new JsonArray();
        addRemoveArguments(removeArgs);
        removeMarker.add("arguments", removeArgs);
        subcommands.add(removeMarker);

        markerCommand.add("subcommands", subcommands);
        DynamicCommandHandler.registerCommand(markerCommand);
    }

    private static void addCreateSetArguments(JsonArray args) {
        JsonObject id = new JsonObject();
        id.addProperty("name", "id");
        id.addProperty("type", "string");
        args.add(id);

        JsonObject data = new JsonObject();
        data.addProperty("name", "data");
        data.addProperty("type", "string");
        args.add(data);
    }

    private static void addRemoveSetArguments(JsonArray args) {
        JsonObject id = new JsonObject();
        id.addProperty("name", "id");
        id.addProperty("type", "string");
        args.add(id);
    }

    private static void addMarkerArguments(JsonArray args) {
        JsonObject markerSet = new JsonObject();
        markerSet.addProperty("name", "markerset");
        markerSet.addProperty("type", "string");
        args.add(markerSet);

        JsonObject markerId = new JsonObject();
        markerId.addProperty("name", "markerid");
        markerId.addProperty("type", "string");
        args.add(markerId);

        JsonObject markerType = new JsonObject();
        markerType.addProperty("name", "type");
        markerType.addProperty("type", "string");
        args.add(markerType);

        JsonObject markerData = new JsonObject();
        markerData.addProperty("name", "data");
        markerData.addProperty("type", "string");
        args.add(markerData);
    }

    private static void addRemoveArguments(JsonArray args) {
        JsonObject markerSet = new JsonObject();
        markerSet.addProperty("name", "markerset");
        markerSet.addProperty("type", "string");
        args.add(markerSet);

        JsonObject markerId = new JsonObject();
        markerId.addProperty("name", "markerid");
        markerId.addProperty("type", "string");
        args.add(markerId);
    }

    public static void handleMarkerCommand(JsonObject data) {
        if (!isEnabled) {
            Denorite.LOGGER.warn("BlueMap integration is not enabled");
            return;
        }

        try {
            if (!data.has("subcommand") || !data.has("arguments")) {
                throw new IllegalArgumentException(
                    "Missing required fields: subcommand and/or arguments"
                );
            }

            String subcommand = data.get("subcommand").getAsString();
            JsonObject args = data.get("arguments").getAsJsonObject();

            // Validate data field is present when required
            switch (subcommand) {
                case "createSet" -> {
                    if (!args.has("id") || !args.has("data")) {
                        throw new IllegalArgumentException(
                            "createSet requires id and data fields"
                        );
                    }
                    createMarkerSet(
                        args.get("id").getAsString(),
                        args.get("data").getAsString()
                    );
                }
                case "removeSet" -> {
                    if (!args.has("id")) {
                        throw new IllegalArgumentException(
                            "removeSet requires id field"
                        );
                    }
                    removeMarkerSet(args.get("id").getAsString());
                }
                case "listSets" -> {
                    listMarkerSets();
                }
                case "add" -> {
                    if (
                        !args.has("markerset") ||
                        !args.has("markerid") ||
                        !args.has("type") ||
                        !args.has("data")
                    ) {
                        throw new IllegalArgumentException(
                            "add requires markerset, markerid, type, and data fields"
                        );
                    }
                    addMarker(
                        args.get("markerset").getAsString(),
                        args.get("markerid").getAsString(),
                        args.get("type").getAsString(),
                        args.get("data").getAsString()
                    );
                }
                case "remove" -> {
                    if (!args.has("markerset") || !args.has("markerid")) {
                        throw new IllegalArgumentException(
                            "remove requires markerset and markerid fields"
                        );
                    }
                    removeMarker(
                        args.get("markerset").getAsString(),
                        args.get("markerid").getAsString()
                    );
                }
                default -> throw new IllegalArgumentException(
                    "Unknown subcommand: " + subcommand
                );
            }
        } catch (Exception e) {
            LOGGER.error("Error in handleMarkerCommand: " + e.getMessage());
            LOGGER.error("Command data: " + data.toString());
            throw e;
        }
    }

    private static void createMarkerSet(String id, String markerSetData) {
        try {
            BlueMapAPI.getInstance()
                .ifPresent(api -> {
                    JsonObject data = JsonParser.parseString(
                        markerSetData
                    ).getAsJsonObject();

                    MarkerSet set = MarkerSet.builder()
                        .label(
                            data.has("label")
                                ? data.get("label").getAsString()
                                : id
                        )
                        .toggleable(
                            data.has("toggleable")
                                ? data.get("toggleable").getAsBoolean()
                                : true
                        )
                        .defaultHidden(
                            data.has("defaultHidden")
                                ? data.get("defaultHidden").getAsBoolean()
                                : false
                        )
                        .build();

                    markerSets.put(id, set);

                    api
                        .getWorlds()
                        .forEach(world ->
                            world
                                .getMaps()
                                .forEach(map -> map.getMarkerSets().put(id, set)
                                )
                        );

                    JsonObject response = new JsonObject();
                    response.addProperty("id", id);
                    response.addProperty("label", set.getLabel());
                    Denorite.sendToTypeScript("bluemap_set_created", response);
                });
        } catch (Exception e) {
            LOGGER.error("Error creating marker set: " + e.getMessage());
        }
    }

    private static void removeMarkerSet(String id) {
        MarkerSet set = markerSets.remove(id);
        if (set != null) {
            BlueMapAPI.getInstance()
                .ifPresent(api -> {
                    api
                        .getWorlds()
                        .forEach(world ->
                            world
                                .getMaps()
                                .forEach(map -> map.getMarkerSets().remove(id))
                        );

                    JsonObject response = new JsonObject();
                    response.addProperty("id", id);
                    Denorite.sendToTypeScript("bluemap_set_removed", response);
                });
        }
    }

    private static void listMarkerSets() {
        JsonArray sets = new JsonArray();
        markerSets.forEach((id, set) -> {
            JsonObject setData = new JsonObject();
            setData.addProperty("id", id);
            setData.addProperty("label", set.getLabel());
            setData.addProperty("toggleable", set.isToggleable());
            setData.addProperty("defaultHidden", set.isDefaultHidden());
            sets.add(setData);
        });

        Denorite.sendToTypeScript("bluemap_sets", sets.getAsJsonObject());
    }

    private static void addMarker(
        String markerSetId,
        String markerId,
        String type,
        String markerData
    ) {
        try {
            BlueMapAPI.getInstance()
                .ifPresent(api -> {
                    JsonObject data = JsonParser.parseString(
                        markerData
                    ).getAsJsonObject();

                    MarkerSet set = markerSets.computeIfAbsent(
                        markerSetId,
                        id -> {
                            MarkerSet newSet = MarkerSet.builder()
                                .label(
                                    data.has("setLabel")
                                        ? data.get("setLabel").getAsString()
                                        : markerSetId
                                )
                                .toggleable(true)
                                .defaultHidden(false)
                                .build();

                            api
                                .getWorlds()
                                .forEach(world ->
                                    world
                                        .getMaps()
                                        .forEach(map ->
                                            map
                                                .getMarkerSets()
                                                .put(markerSetId, newSet)
                                        )
                                );

                            return newSet;
                        }
                    );

                    Marker marker = createMarker(type, data);
                    if (marker != null) {
                        set.getMarkers().put(markerId, marker);
                    }
                });
        } catch (Exception e) {
            LOGGER.error("Error adding marker: " + e.getMessage());
        }
    }

    private static Marker createMarker(String type, JsonObject data) {
        return switch (type.toLowerCase()) {
            case "poi" -> createPOIMarker(data);
            case "html" -> createHTMLMarker(data);
            case "line" -> createLineMarker(data);
            case "shape" -> createShapeMarker(data);
            case "extrude" -> createExtrudeMarker(data);
            default -> null;
        };
    }

    private static POIMarker createPOIMarker(JsonObject data) {
        Vector3d pos = parsePosition(data.get("position").getAsJsonObject());
        return POIMarker.builder()
            .label(data.get("label").getAsString())
            .position(pos)
            .icon(
                data.has("icon")
                    ? data.get("icon").getAsString()
                    : "assets/poi.svg",
                16,
                16
            )
            .maxDistance(
                data.has("maxDistance")
                    ? data.get("maxDistance").getAsDouble()
                    : 1000.0
            )
            .minDistance(
                data.has("minDistance")
                    ? data.get("minDistance").getAsDouble()
                    : 0.0
            )
            .build();
    }

    private static HtmlMarker createHTMLMarker(JsonObject data) {
        Vector3d pos = parsePosition(data.get("position").getAsJsonObject());
        return HtmlMarker.builder()
            .label(data.get("label").getAsString())
            .position(pos)
            .html(data.get("html").getAsString())
            .maxDistance(
                data.has("maxDistance")
                    ? data.get("maxDistance").getAsDouble()
                    : 1000.0
            )
            .minDistance(
                data.has("minDistance")
                    ? data.get("minDistance").getAsDouble()
                    : 0.0
            )
            .build();
    }

    private static LineMarker createLineMarker(JsonObject data) {
        List<Vector3d> points = new ArrayList<>();
        for (JsonElement point : data.get("line").getAsJsonArray()) {
            points.add(parsePosition(point.getAsJsonObject()));
        }

        Line.Builder lineBuilder = Line.builder();
        for (Vector3d point : points) {
            lineBuilder.addPoint(point);
        }
        Line line = lineBuilder.build();

        return LineMarker.builder()
            .label(data.get("label").getAsString())
            .line(line)
            .lineWidth(
                data.has("lineWidth") ? data.get("lineWidth").getAsInt() : 2
            )
            .maxDistance(
                data.has("maxDistance")
                    ? data.get("maxDistance").getAsDouble()
                    : 1000.0
            )
            .minDistance(
                data.has("minDistance")
                    ? data.get("minDistance").getAsDouble()
                    : 0.0
            )
            .build();
    }

    private static ShapeMarker createShapeMarker(JsonObject data) {
        List<Vector3d> points = new ArrayList<>();
        for (JsonElement point : data.get("shape").getAsJsonArray()) {
            JsonObject pos = point.getAsJsonObject();
            points.add(
                new Vector3d(
                    pos.get("x").getAsDouble(),
                    data.get("shapeY").getAsDouble(),
                    pos.get("z").getAsDouble()
                )
            );
        }

        double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;

        for (Vector3d point : points) {
            minX = Math.min(minX, point.getX());
            minZ = Math.min(minZ, point.getZ());
            maxX = Math.max(maxX, point.getX());
            maxZ = Math.max(maxZ, point.getZ());
        }

        Shape shape = Shape.createRect(minX, minZ, maxX, maxZ);
        float shapeY = data.get("shapeY").getAsFloat();

        return ShapeMarker.builder()
            .label(data.get("label").getAsString())
            .shape(shape, shapeY)
            .lineColor(parseColor(data.get("lineColor").getAsJsonObject()))
            .fillColor(parseColor(data.get("fillColor").getAsJsonObject()))
            .lineWidth(
                data.has("lineWidth") ? data.get("lineWidth").getAsInt() : 2
            )
            .maxDistance(
                data.has("maxDistance")
                    ? data.get("maxDistance").getAsDouble()
                    : 1000.0
            )
            .minDistance(
                data.has("minDistance")
                    ? data.get("minDistance").getAsDouble()
                    : 0.0
            )
            .build();
    }

    private static ExtrudeMarker createExtrudeMarker(JsonObject data) {
        List<Vector3d> points = new ArrayList<>();
        for (JsonElement point : data.get("shape").getAsJsonArray()) {
            JsonObject pos = point.getAsJsonObject();
            points.add(
                new Vector3d(
                    pos.get("x").getAsDouble(),
                    0,
                    pos.get("z").getAsDouble()
                )
            );
        }

        double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;

        for (Vector3d point : points) {
            minX = Math.min(minX, point.getX());
            minZ = Math.min(minZ, point.getZ());
            maxX = Math.max(maxX, point.getX());
            maxZ = Math.max(maxZ, point.getZ());
        }

        Shape shape = Shape.createRect(minX, minZ, maxX, maxZ);
        float minY = data.get("shapeMinY").getAsFloat();
        float maxY = data.get("shapeMaxY").getAsFloat();

        return ExtrudeMarker.builder()
            .label(data.get("label").getAsString())
            .shape(shape, minY, maxY)
            .lineColor(parseColor(data.get("lineColor").getAsJsonObject()))
            .fillColor(parseColor(data.get("fillColor").getAsJsonObject()))
            .lineWidth(
                data.has("lineWidth") ? data.get("lineWidth").getAsInt() : 2
            )
            .maxDistance(
                data.has("maxDistance")
                    ? data.get("maxDistance").getAsDouble()
                    : 1000.0
            )
            .minDistance(
                data.has("minDistance")
                    ? data.get("minDistance").getAsDouble()
                    : 0.0
            )
            .build();
    }

    private static void removeMarker(String markerSetId, String markerId) {
        MarkerSet set = markerSets.get(markerSetId);
        if (set != null) {
            set.getMarkers().remove(markerId);
        }
    }

    private static Vector3d parsePosition(JsonObject pos) {
        return new Vector3d(
            pos.get("x").getAsDouble(),
            pos.get("y").getAsDouble(),
            pos.get("z").getAsDouble()
        );
    }

    private static Color parseColor(JsonObject color) {
        int alpha = color.has("a") ? color.get("a").getAsInt() : 255;
        float normalizedAlpha = alpha / 255f; // Convert to float between 0-1

        return new Color(
                color.get("r").getAsInt(),
                color.get("g").getAsInt(),
                color.get("b").getAsInt(),
                normalizedAlpha
        );
    }
}
