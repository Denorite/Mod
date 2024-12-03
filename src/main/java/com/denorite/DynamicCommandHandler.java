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
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

import static com.denorite.Denorite.LOGGER;

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
        previousCommands = new HashMap<>(registeredCommands);
        isReconnecting = true;
        LOGGER.info("Preparing for reconnection, maintaining command file until connection confirmed");
    }

    public static void confirmReconnect() {
        if (isReconnecting) {
            registeredCommands.clear();
            saveCommands();
            LOGGER.info("Connection confirmed, cleared commands file");
            isReconnecting = false;
        }
    }

    private static void loadCommands() {
        File file = new File(COMMANDS_FILE);
        if (!file.exists()) {
            LOGGER.info("Command file does not exist. Creating a new one.");
            saveCommands();
            return;
        }

        try {
            String content = Files.readString(file.toPath());
            if (content.trim().isEmpty()) {
                LOGGER.info("Empty command file, initializing new one");
                saveCommands();
                return;
            }

            JsonArray jsonArray = JsonParser.parseString(content).getAsJsonArray();
            registeredCommands.clear();

            for (JsonElement element : jsonArray) {
                try {
                    if (element.isJsonObject()) {
                        JsonObject commandData = element.getAsJsonObject();
                        String name = commandData.get("name").getAsString();
                        registeredCommands.put(name, commandData);
                    }
                } catch (Exception e) {
                    LOGGER.error("Error parsing command: " + e.getMessage());
                }
            }

            LOGGER.info("Loaded " + registeredCommands.size() + " custom commands");
        } catch (Exception e) {
            LOGGER.error("Error loading commands: " + e.getMessage(), e);
            // Create backup of problematic file
            if (file.exists()) {
                try {
                    Files.copy(file.toPath(),
                            file.toPath().resolveSibling("custom_commands.json.backup"),
                            StandardCopyOption.REPLACE_EXISTING);
                    LOGGER.info("Created backup of problematic commands file");
                } catch (IOException backupError) {
                    LOGGER.error("Failed to create backup: " + backupError.getMessage());
                }
            }
            // Reset commands
            registeredCommands.clear();
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
        } catch (IOException e) {
            LOGGER.error("Error saving commands: " + e.getMessage());
        }
    }

    public static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            for (Map.Entry<String, JsonObject> entry : registeredCommands.entrySet()) {
                String commandName = entry.getKey();
                JsonObject commandData = entry.getValue();

                // Only create base command if it has direct arguments or no subcommands
                boolean shouldRegisterBase = !commandData.has("subcommands") ||
                        (commandData.has("arguments") && commandData.getAsJsonArray("arguments").size() > 0);

                if (shouldRegisterBase) {
                    LiteralArgumentBuilder<ServerCommandSource> command = CommandManager.literal(commandName);
                    command.executes(context -> executeCommand(context, commandData, commandName, null));

                    if (commandData.has("arguments")) {
                        addArguments(command, commandData, commandName, null, registryAccess);
                    }

                    dispatcher.register(command);
                    LOGGER.info("Registered command: " + commandName);
                }

                // Register subcommands separately
                if (commandData.has("subcommands")) {
                    JsonArray subcommands = commandData.getAsJsonArray("subcommands");
                    for (JsonElement subcommandElement : subcommands) {
                        JsonObject subcommand = subcommandElement.getAsJsonObject();
                        String subcommandName = subcommand.get("name").getAsString();

                        // Create the full command path
                        LiteralArgumentBuilder<ServerCommandSource> fullCommand = CommandManager.literal(commandName)
                                .then(addSubcommand(subcommand, commandName, registryAccess));

                        dispatcher.register(fullCommand);
                        LOGGER.info("Registered subcommand: " + commandName + " " + subcommandName);
                    }
                }
            }
        });
    }

    private static LiteralArgumentBuilder<ServerCommandSource> addSubcommand(JsonObject subcommand, String commandName, CommandRegistryAccess registryAccess) {
        String subcommandName = subcommand.get("name").getAsString();
        LiteralArgumentBuilder<ServerCommandSource> subcommandBuilder = CommandManager.literal(subcommandName);

        // Add execution for subcommand without arguments
        subcommandBuilder.executes(context -> executeCommand(context, subcommand, commandName, subcommandName));

        // Add arguments if they exist
        if (subcommand.has("arguments")) {
            addArguments(subcommandBuilder, subcommand, commandName, subcommandName, registryAccess);
        }

        return subcommandBuilder;
    }

    private static void addArguments(ArgumentBuilder<ServerCommandSource, ?> builder, JsonObject commandData,
                                     String commandName, String subcommandName, CommandRegistryAccess registryAccess) {
        if (commandData.has("arguments")) {
            JsonArray arguments = commandData.getAsJsonArray("arguments");
            addArgumentsRecursive(builder, arguments, 0, commandData, commandName, subcommandName, registryAccess);
        }
    }

    private static void addArgumentsRecursive(ArgumentBuilder<ServerCommandSource, ?> builder, JsonArray arguments,
                                              int index, JsonObject commandData, String commandName,
                                              String subcommandName, CommandRegistryAccess registryAccess) {
        if (index >= arguments.size()) {
            builder.executes(context -> executeCommand(context, commandData, commandName, subcommandName));
            return;
        }

        JsonObject arg = arguments.get(index).getAsJsonObject();
        String argName = arg.get("name").getAsString();
        String argType = arg.get("type").getAsString();

        RequiredArgumentBuilder<ServerCommandSource, ?> argument = switch (argType) {
            case "string" -> CommandManager.argument(argName, StringArgumentType.string());
            case "integer" -> CommandManager.argument(argName, IntegerArgumentType.integer());
            case "player" -> CommandManager.argument(argName, EntityArgumentType.player());
            case "item" -> CommandManager.argument(argName, ItemStackArgumentType.itemStack(registryAccess));
            default -> CommandManager.argument(argName, StringArgumentType.word());
        };

        addArgumentsRecursive(argument, arguments, index + 1, commandData, commandName, subcommandName, registryAccess);
        builder.then(argument);
    }

    private static int executeCommand(CommandContext<ServerCommandSource> context, JsonObject commandData,
                                      String commandName, String subcommandName) {
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
                    // Skip arguments that don't exist in this context
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
                // Only register if it has no subcommands or is a complete command path
                boolean shouldRegisterBase = !commandData.has("subcommands") ||
                        (commandData.has("arguments") && commandData.getAsJsonArray("arguments").size() > 0);

                if (shouldRegisterBase) {
                    LiteralArgumentBuilder<ServerCommandSource> command = CommandManager.literal(name)
                            .executes(context -> {
                                context.getSource().sendFeedback(() -> Text.of("Command registered. Please restart the server to use it."), false);
                                return 1;
                            });

                    Denorite.server.getCommandManager().getDispatcher().register(command);
                    LOGGER.info("Dynamically registered placeholder for command: " + name);
                    LOGGER.info("Restart the server to fully register the command with all its arguments and subcommands.");
                } else {
                    LOGGER.info("Skipping base command registration for: " + name + " as it only contains subcommands");
                }
            } else {
                LOGGER.info("Command already exists with same structure, maintaining registration: " + name);
            }
        }
    }

    public static void unregisterCommand(String name) {
        registeredCommands.remove(name);
        saveCommands();
        LOGGER.info("Unregistered custom command: " + name);
    }

    public static void clearCommands() {
        registeredCommands.clear();
        saveCommands();
        LOGGER.info("Cleared all custom commands");
    }
}
