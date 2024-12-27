package com.denorite;

import com.google.gson.*;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
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
                boolean hasSubcommands = commandData.has("subcommands");
                boolean hasArguments = commandData.has("arguments") &&
                        !commandData.getAsJsonArray("arguments").isEmpty();
                boolean isExecutable = !hasSubcommands || hasArguments;  // Can execute if no subcommands or has own arguments
                boolean shouldRegisterBase = true;  // Always register base command

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

    private static void addArguments(ArgumentBuilder<ServerCommandSource, ?> builder, JsonObject commandData,
                                     String commandName, String subcommandName, CommandRegistryAccess registryAccess) {
        if (commandData.has("arguments")) {
            JsonArray arguments = commandData.getAsJsonArray("arguments");
            addArgumentsRecursive(builder, arguments, 0, commandData, commandName, subcommandName, registryAccess, true);
        }
    }

    private static void addArgumentsRecursive(ArgumentBuilder<ServerCommandSource, ?> builder, JsonArray arguments,
                                              int index, JsonObject commandData, String commandName,
                                              String subcommandName, CommandRegistryAccess registryAccess, boolean allowPartial) {
        // Handle case with no arguments
        if (allowPartial) {
            builder.executes(context -> executeCommand(context, commandData, commandName, subcommandName));
        }

        if (index >= arguments.size()) {
            return;
        }

        JsonObject arg = arguments.get(index).getAsJsonObject();
        String argName = arg.get("name").getAsString();
        String argType = arg.get("type").getAsString();
        boolean isOptional = arg.has("optional") && arg.get("optional").getAsBoolean();

        // Create argument builder with appropriate type and suggestions
        RequiredArgumentBuilder<ServerCommandSource, ?> argument = createArgumentBuilder(argName, argType, registryAccess);

        // Add suggestions based on argument type
        addSuggestions(argument, arg);

        // Handle optional arguments by creating a branch without this argument
        if (isOptional && allowPartial) {
            // Create a path that skips this optional argument
            addArgumentsRecursive(builder, arguments, index + 1, commandData, commandName, subcommandName, registryAccess, true);
        }

        // Continue with the regular path including this argument
        addArgumentsRecursive(argument, arguments, index + 1, commandData, commandName, subcommandName, registryAccess, true);
        builder.then(argument);
    }

    private static RequiredArgumentBuilder<ServerCommandSource, ?> createArgumentBuilder(String argName, String argType, CommandRegistryAccess registryAccess) {
        return switch (argType.toLowerCase()) {
            case "string" -> {
                // For string arguments, use quoted string to properly handle spaces and quotes
                yield CommandManager.argument(argName, StringArgumentType.string());
            }
            case "text" -> {
                // For text arguments that need to be greedy (consume rest of line)
                yield CommandManager.argument(argName, StringArgumentType.greedyString());
            }
            case "integer" -> CommandManager.argument(argName, IntegerArgumentType.integer());
            case "player" -> CommandManager.argument(argName, EntityArgumentType.player());
            case "item" -> CommandManager.argument(argName, ItemStackArgumentType.itemStack(registryAccess));
            default -> CommandManager.argument(argName, StringArgumentType.word());
        };
    }

    private static void addSuggestions(RequiredArgumentBuilder<ServerCommandSource, ?> argument, JsonObject arg) {
        String argType = arg.get("type").getAsString();
        String argName = arg.get("name").getAsString();

        // Custom suggestion provider based on argument type
        SuggestionProvider<ServerCommandSource> suggestionProvider = (context, builder) -> {
            switch (argType.toLowerCase()) {
                case "string":
                    if (arg.has("suggestions")) {
                        JsonArray suggestions = arg.getAsJsonArray("suggestions");
                        for (JsonElement suggestion : suggestions) {
                            builder.suggest(suggestion.getAsString());
                        }
                    }
                    break;

                case "player":
                    // Let Minecraft handle player suggestions
                    return Suggestions.empty();

                case "item":
                    // Let Minecraft handle item suggestions
                    return Suggestions.empty();
            }
            return builder.buildFuture();
        };

        // Only add custom suggestions if the argument type needs them
        if (argType.equals("string") && arg.has("suggestions")) {
            argument.suggests(suggestionProvider);
        }
    }

    private static LiteralArgumentBuilder<ServerCommandSource> addSubcommand(JsonObject subcommand, String commandName, CommandRegistryAccess registryAccess) {
        String subcommandName = subcommand.get("name").getAsString();
        LiteralArgumentBuilder<ServerCommandSource> subcommandBuilder = CommandManager.literal(subcommandName);

        // Add execution for subcommand without arguments
        if (!subcommand.has("arguments") || subcommand.getAsJsonArray("arguments").size() == 0) {
            subcommandBuilder.executes(context -> executeCommand(context, subcommand, commandName, subcommandName));
        }

        // Add arguments if they exist
        if (subcommand.has("arguments")) {
            addArguments(subcommandBuilder, subcommand, commandName, subcommandName, registryAccess);
        }

        return subcommandBuilder;
    }

    public static void registerCommand(JsonObject commandData) {
        String name = commandData.get("name").getAsString();

        LiteralArgumentBuilder<ServerCommandSource> command = CommandManager.literal(name);

        // Add basic command execution if it has no arguments
        if (!commandData.has("arguments") || commandData.getAsJsonArray("arguments").isEmpty()) {
            command.executes(context -> {
                if (commandData.has("description")) {
                    context.getSource().sendFeedback(() ->
                            Text.of(commandData.get("description").getAsString()), false);
                }
                return 1;
            });
        }

        // Add usage information
        if (commandData.has("usage")) {
            String usage = commandData.get("usage").getAsString();
            command.executes(context -> {
                context.getSource().sendFeedback(() ->
                        Text.of("Usage: /" + name + " " + usage), false);
                return 1;
            });
        }

        registeredCommands.put(name, commandData);
        saveCommands();

        if (Denorite.server != null) {
            Denorite.server.getCommandManager().getDispatcher().register(command);
            LOGGER.info("Registered command: " + name);
        }
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
