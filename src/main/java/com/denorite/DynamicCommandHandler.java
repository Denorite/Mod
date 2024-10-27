package com.denorite;

import com.google.gson.*;
import com.mojang.brigadier.builder.ArgumentBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.ItemStackArgumentType;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

public class DynamicCommandHandler {
    private static final Map<String, JsonObject> registeredCommands = new HashMap<>();
    private static final String COMMANDS_FILE = "custom_commands.json";
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private static Map<String, JsonObject> previousCommands = new HashMap<>();
    private static boolean isReconnecting = false;

    public static void initialize() {
        loadCommands();
        registerCommands();
    }

    public static void handleReconnect() {
        // Store current commands for comparison
        previousCommands = new HashMap<>(registeredCommands);
        isReconnecting = true;
        Denorite.LOGGER.info("Preparing for reconnection, maintaining command file until connection confirmed");
    }

    public static void confirmReconnect() {
        if (isReconnecting) {
            registeredCommands.clear();
            saveCommands(); // Now we clear the JSON file
            Denorite.LOGGER.info("Connection confirmed, cleared commands file");
            isReconnecting = false;
        }
    }

    private static void loadCommands() {
        File file = new File(COMMANDS_FILE);
        if (file.exists()) {
            try (Reader reader = new FileReader(file)) {
                JsonElement jsonElement = JsonParser.parseReader(reader);
                if (jsonElement.isJsonArray()) {
                    JsonArray jsonArray = jsonElement.getAsJsonArray();
                    for (JsonElement element : jsonArray) {
                        if (element.isJsonObject()) {
                            JsonObject commandData = element.getAsJsonObject();
                            String name = commandData.get("name").getAsString();
                            registeredCommands.put(name, commandData);
                        }
                    }
                    Denorite.LOGGER.info("Loaded " + registeredCommands.size() + " custom commands");
                } else {
                    Denorite.LOGGER.warn("Command file is not a valid JSON array. No commands loaded.");
                }
            } catch (IOException e) {
                Denorite.LOGGER.error("Error loading commands: " + e.getMessage());
            } catch (JsonParseException e) {
                Denorite.LOGGER.error("Error parsing command file: " + e.getMessage());
            }
        } else {
            Denorite.LOGGER.info("Command file does not exist. Creating a new one.");
            saveCommands();
        }
    }

    private static void saveCommands() {
        try (Writer writer = new FileWriter(COMMANDS_FILE)) {
            JsonArray jsonArray = new JsonArray();
            for (JsonObject commandData : registeredCommands.values()) {
                jsonArray.add(commandData);
            }
            gson.toJson(jsonArray, writer);
            Denorite.LOGGER.info("Saved " + registeredCommands.size() + " custom commands");
        } catch (IOException e) {
            Denorite.LOGGER.error("Error saving commands: " + e.getMessage());
        }
    }

    public static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            for (Map.Entry<String, JsonObject> entry : registeredCommands.entrySet()) {
                String commandName = entry.getKey();
                JsonObject commandData = entry.getValue();

                LiteralArgumentBuilder<ServerCommandSource> command = CommandManager.literal(commandName);

                if (commandData.has("subcommands")) {
                    JsonArray subcommands = commandData.getAsJsonArray("subcommands");
                    for (JsonElement subcommandElement : subcommands) {
                        JsonObject subcommand = subcommandElement.getAsJsonObject();
                        command.then(addSubcommand(subcommand, commandName, registryAccess));
                    }
                } else {
                    addArguments(command, commandData, commandName, null, registryAccess);
                }

                dispatcher.register(command);
                Denorite.LOGGER.info("Registered command: " + commandName);
            }
        });
    }

    private static LiteralArgumentBuilder<ServerCommandSource> addSubcommand(JsonObject subcommand, String commandName, CommandRegistryAccess registryAccess) {
        String subcommandName = subcommand.get("name").getAsString();
        LiteralArgumentBuilder<ServerCommandSource> subcommandBuilder = CommandManager.literal(subcommandName);
        addArguments(subcommandBuilder, subcommand, commandName, subcommandName, registryAccess);
        return subcommandBuilder;
    }

    private static void addArguments(ArgumentBuilder<ServerCommandSource, ?> builder, JsonObject commandData, String commandName, String subcommandName, CommandRegistryAccess registryAccess) {
        if (commandData.has("arguments")) {
            JsonArray arguments = commandData.getAsJsonArray("arguments");
            addArgumentsRecursive(builder, arguments, 0, commandData, commandName, subcommandName, registryAccess);
        } else {
            builder.executes(context -> executeCommand(context, commandData, commandName, subcommandName));
        }
    }

    private static void addArgumentsRecursive(ArgumentBuilder<ServerCommandSource, ?> builder, JsonArray arguments, int index,
                                              JsonObject commandData, String commandName, String subcommandName, CommandRegistryAccess registryAccess) {
        if (index >= arguments.size()) {
            builder.executes(context -> executeCommand(context, commandData, commandName, subcommandName));
            return;
        }

        JsonObject arg = arguments.get(index).getAsJsonObject();
        String argName = arg.get("name").getAsString();
        String argType = arg.get("type").getAsString();

        ArgumentBuilder<ServerCommandSource, ?> argument = switch (argType) {
            case "string" -> CommandManager.argument(argName, StringArgumentType.string());
            case "integer" -> CommandManager.argument(argName, IntegerArgumentType.integer());
            case "player" -> CommandManager.argument(argName, EntityArgumentType.player());
            case "item" -> CommandManager.argument(argName, ItemStackArgumentType.itemStack(registryAccess));
            default -> CommandManager.argument(argName, StringArgumentType.word());
        };

        addArgumentsRecursive(argument, arguments, index + 1, commandData, commandName, subcommandName, registryAccess);
        builder.then(argument);
    }

    private static int executeCommand(CommandContext<ServerCommandSource> context, JsonObject commandData, String commandName, String subcommandName) {
        ServerCommandSource source = context.getSource();

        JsonObject executionData = new JsonObject();
        executionData.addProperty("command", commandName);
        if (subcommandName != null) {
            executionData.addProperty("subcommand", subcommandName);
        }

        // Add sender information
        String senderName = source.getName();
        String senderType = source.getEntity() instanceof net.minecraft.server.network.ServerPlayerEntity ? "player" : "console";
        executionData.addProperty("sender", senderName);
        executionData.addProperty("senderType", senderType);

        if (commandData.has("arguments")) {
            JsonObject args = new JsonObject();
            JsonArray arguments = commandData.getAsJsonArray("arguments");
            for (JsonElement argElement : arguments) {
                JsonObject arg = argElement.getAsJsonObject();
                String argName = arg.get("name").getAsString();
                String argType = arg.get("type").getAsString();

                try {
                    switch (argType) {
                        case "string" -> args.addProperty(argName, StringArgumentType.getString(context, argName));
                        case "integer" -> args.addProperty(argName, IntegerArgumentType.getInteger(context, argName));
                        case "player" -> args.addProperty(argName, EntityArgumentType.getPlayer(context, argName).getName().getString());
                        case "item" -> {
                            ItemStack itemStack = ItemStackArgumentType.getItemStackArgument(context, argName).createStack(1, false);
                            args.addProperty(argName, itemStack.getItem().getTranslationKey());
                        }
                        default -> args.addProperty(argName, context.getArgument(argName, String.class));
                    }
                } catch (Exception e) {
                    Denorite.LOGGER.warn("Failed to get argument " + argName + ": " + e.getMessage());
                }
            }
            executionData.add("arguments", args);
        }

        Denorite.sendToTypeScript("custom_command_executed", executionData);
        return 1;
    }

    public static void registerCommand(JsonObject commandData) {
        String name = commandData.get("name").getAsString();
        JsonObject previousCommand = previousCommands.get(name);

        registeredCommands.put(name, commandData);
        saveCommands();

        if (Denorite.server != null) {
            if (previousCommand == null || !previousCommand.toString().equals(commandData.toString())) {
                Denorite.server.getCommandManager().getDispatcher().register(
                        CommandManager.literal(name).executes(context -> {
                            context.getSource().sendFeedback(() -> Text.of("Command registered. Please restart the server to use it."), false);
                            return 1;
                        })
                );
                Denorite.LOGGER.info("Dynamically registered placeholder for command: " + name);
                Denorite.LOGGER.info("Restart the server to fully register the command with all its arguments and subcommands.");
            } else {
                Denorite.LOGGER.info("Command already exists with same structure, maintaining registration: " + name);
            }
        } else {
            Denorite.LOGGER.warn("Server not initialized. Command will be registered on next server start: " + name);
        }
    }

    public static void unregisterCommand(String name) {
        registeredCommands.remove(name);
        saveCommands();
        Denorite.LOGGER.info("Unregistered custom command: " + name);
    }

    public static void clearCommands() {
        registeredCommands.clear();
        saveCommands();
        Denorite.LOGGER.info("Cleared all custom commands");
    }
}
