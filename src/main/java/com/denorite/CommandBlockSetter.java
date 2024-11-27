package com.denorite;

import net.minecraft.block.Blocks;
import net.minecraft.block.entity.CommandBlockBlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.BlockState;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.Direction;

public class CommandBlockSetter {
    /**
     * Sets a command block at the specified position with custom NBT data
     */
    public static void setCommandBlock(ServerWorld world, BlockPos pos, String command, boolean auto, boolean conditional) {
        // Set the command block
        BlockState commandBlockState = Blocks.COMMAND_BLOCK.getDefaultState();
        world.setBlockState(pos, commandBlockState);

        // Get the block entity
        CommandBlockBlockEntity commandBlock = (CommandBlockBlockEntity) world.getBlockEntity(pos);
        if (commandBlock != null) {
            // Create NBT data
            NbtCompound nbt = new NbtCompound();
            nbt.putString("Command", command);
            nbt.putByte("auto", (byte) (auto ? 1 : 0));
            nbt.putByte("conditional", (byte) (conditional ? 1 : 0));
            nbt.putBoolean("TrackOutput", true);

            // Apply NBT to block entity
            commandBlock.readNbt(nbt);

            // Update the command block
            commandBlock.markDirty();
            world.updateListeners(pos, commandBlockState, commandBlockState, 3);
        }
    }

    /**
     * Sets a command block with more detailed configuration options
     */
    public static void setAdvancedCommandBlock(ServerWorld world, BlockPos pos,
                                               CommandBlockConfig config) {
        BlockState commandBlockState = switch (config.type) {
            case REPEATING -> Blocks.REPEATING_COMMAND_BLOCK.getDefaultState();
            case CHAIN -> Blocks.CHAIN_COMMAND_BLOCK.getDefaultState();
            default -> Blocks.COMMAND_BLOCK.getDefaultState();
        };

        // Set facing direction if specified
        if (config.facing != null) {
            commandBlockState = commandBlockState.with(Properties.FACING, config.facing);
        }

        world.setBlockState(pos, commandBlockState);

        CommandBlockBlockEntity commandBlock = (CommandBlockBlockEntity) world.getBlockEntity(pos);
        if (commandBlock != null) {
            NbtCompound nbt = new NbtCompound();
            nbt.putString("Command", config.command);
            nbt.putByte("auto", (byte) (config.auto ? 1 : 0));
            nbt.putByte("conditional", (byte) (config.conditional ? 1 : 0));
            nbt.putBoolean("TrackOutput", config.trackOutput);

            if (config.customName != null) {
                nbt.putString("CustomName", config.customName);
            }

            commandBlock.readNbt(nbt);
            commandBlock.markDirty();
            world.updateListeners(pos, commandBlockState, commandBlockState, 3);
        }
    }

    /**
     * Configuration class for command blocks
     */
    public static class CommandBlockConfig {
        public enum Type {
            IMPULSE, REPEATING, CHAIN
        }

        String command;
        Type type = Type.IMPULSE;
        Direction facing = null;
        boolean auto = false;
        boolean conditional = false;
        boolean trackOutput = true;
        String customName = null;

        public CommandBlockConfig(String command) {
            this.command = command;
        }

        public CommandBlockConfig setType(Type type) {
            this.type = type;
            return this;
        }

        public CommandBlockConfig setFacing(Direction facing) {
            this.facing = facing;
            return this;
        }

        public CommandBlockConfig setAuto(boolean auto) {
            this.auto = auto;
            return this;
        }

        public CommandBlockConfig setConditional(boolean conditional) {
            this.conditional = conditional;
            return this;
        }

        public CommandBlockConfig setCustomName(String name) {
            this.customName = name;
            return this;
        }
    }
}
