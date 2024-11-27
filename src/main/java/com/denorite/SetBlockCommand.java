package com.denorite;
import net.minecraft.server.MinecraftServer;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.CommandBlockBlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import com.google.gson.JsonObject;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SetBlockCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger("SetBlockCommand");

    public static String execute(MinecraftServer server, JsonObject data) {
        try {
            // Parse required parameters
            int x = data.get("x").getAsInt();
            int y = data.get("y").getAsInt();
            int z = data.get("z").getAsInt();
            String blockId = data.get("block").getAsString();
            String dimension = data.has("dimension") ? data.get("dimension").getAsString() : "minecraft:overworld";
            String nbtData = data.has("nbt") ? data.get("nbt").getAsString() : null;

            LOGGER.info("Processing setblock command at ({}, {}, {})", x, y, z);

            // Get the world
            ServerWorld world;
            if (dimension.equals("minecraft:overworld")) {
                world = server.getOverworld();
            } else {
                RegistryKey<World> worldKey = RegistryKey.of(RegistryKeys.WORLD, new Identifier(dimension));
                world = server.getWorld(worldKey);
            }

            if (world == null) {
                LOGGER.error("Invalid world");
                return "Error: Invalid dimension";
            }

            BlockPos pos = new BlockPos(x, y, z);

            // Special handling for command blocks
            if (blockId.equals("minecraft:repeating_command_block") && nbtData != null) {
                try {
                    // Ensure chunk is loaded
                    world.getChunk(pos);

                    // Remove any existing block
                    world.removeBlock(pos, false);

                    // Force chunk updates
                    world.setBlockState(pos, Blocks.AIR.getDefaultState(),
                            Block.NOTIFY_ALL | Block.FORCE_STATE );

                    // Parse NBT data
                    NbtCompound nbt = StringNbtReader.parse(nbtData);
                    String command = nbt.getString("Command");
                    LOGGER.info("Parsed command: {}", command);

                    // Create the block state
                    BlockState state = Blocks.REPEATING_COMMAND_BLOCK.getDefaultState();

                    // Place the block and force immediate update
                    boolean placed = world.setBlockState(pos, state,
                            Block.NOTIFY_ALL | Block.FORCE_STATE );

                    if (!placed) {
                        LOGGER.error("Failed to place command block");
                        return "Failed to place command block";
                    }

                    // Get the block entity with retry
                    BlockEntity blockEntity = null;
                    for (int i = 0; i < 3 && blockEntity == null; i++) {
                        // Force chunk sync before getting block entity
                        world.getChunk(pos).setNeedsSaving(true);

                        blockEntity = world.getBlockEntity(pos);
                        if (blockEntity == null) {
                            Thread.sleep(50);
                        }
                    }

                    if (blockEntity instanceof CommandBlockBlockEntity commandBlock) {
                        // Create complete NBT compound
                        NbtCompound fullNbt = new NbtCompound();
                        fullNbt.putString("Command", command);
                        fullNbt.putBoolean("auto", nbt.getBoolean("auto"));
                        fullNbt.putBoolean("powered", nbt.getBoolean("powered"));
                        fullNbt.putBoolean("conditional", false);
                        fullNbt.putByte("conditionMet", (byte)1);
                        fullNbt.putByte("UpdateLastExecution", (byte)1);

                        // Apply NBT to command block
                        commandBlock.readNbt(fullNbt);

                        // Set command directly
                        commandBlock.getCommandExecutor().setCommand(command);
//                        commandBlock.getCommandExecutor().setAutomatic(true);

                        // Mark updates
                        commandBlock.markDirty();
                        world.markDirty(pos);

                        // Force block update and chunk sync
                        world.updateListeners(pos, state, state,
                                Block.NOTIFY_ALL | Block.FORCE_STATE );
                        world.getChunk(pos).setNeedsSaving(true);

                        // Verify command was set
                        String setCommand = commandBlock.getCommandExecutor().getCommand();
                        if (!command.equals(setCommand)) {
                            LOGGER.error("Command verification failed - expected '{}' but got '{}'",
                                    command, setCommand);
                            return "Command verification failed";
                        }

                        LOGGER.info("Command block placed and configured successfully");
                        return "Command block placed and configured successfully";
                    } else {
                        LOGGER.error("Failed to get command block entity after retries");
                        return "Failed to get command block entity";
                    }

                } catch (Exception e) {
                    LOGGER.error("Error setting command block", e);
                    e.printStackTrace();
                    return "Error: " + e.getMessage();
                }
            } else {
                // Handle regular blocks
                Block block = Registries.BLOCK.get(new Identifier(blockId));
                if (block == null) {
                    return "Error: Invalid block ID";
                }

                boolean success = world.setBlockState(pos, block.getDefaultState(), Block.NOTIFY_ALL);
                return success ? "Block placed successfully" : "Failed to place block";
            }

        } catch (Exception e) {
            LOGGER.error("Error in setblock command", e);
            e.printStackTrace();
            return "Error: " + e.getMessage();
        }
    }
}
