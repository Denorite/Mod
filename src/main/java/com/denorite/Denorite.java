package com.denorite;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageTracker;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.advancement.AdvancementEntry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

public class Denorite implements ModInitializer {
	public static final String MOD_ID = "Denorite";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static WebSocket webSocket;
	private static final Gson gson = new Gson();
	static MinecraftServer server;
	private static boolean strictMode = false;
	private static final int RECONNECT_DELAY = 100;

	private DenoriteConfig config;

	private static Block lastInteractedBlock = null;
	private static BlockPos lastInteractedPos = null;

	@Override
	public void onInitialize() {
		LOGGER.info("Initializing Denorite");
		config = new DenoriteConfig();
		initializeWebSocket();
		DynamicCommandHandler.initialize();
		ServerLifecycleEvents.SERVER_STARTING.register(this::setServer);
		ServerLifecycleEvents.SERVER_STOPPING.register(this::unsetServer);
		registerAllEvents();
	}

	private void setServer(MinecraftServer minecraftServer) {
		server = minecraftServer;
		LOGGER.info("Server reference set in Denorite");
	}

	private void unsetServer(MinecraftServer minecraftServer) {
		server = null;
		LOGGER.info("Server reference unset in Denorite");
	}

	private void initializeWebSocket() {
		connectWebSocket();
	}

	private void connectWebSocket() {
		try {
			HttpClient client = HttpClient.newHttpClient();
			String origin = config.getOrigin(); // Assume we've added this to the config
			if (origin == null || origin.isEmpty()) {
				throw new IllegalStateException("Origin must be set in the configuration");
			}

			CompletableFuture<WebSocket> ws = client.newWebSocketBuilder()
					.header("Authorization", "Bearer " + config.getJwtToken())
					.header("Origin", origin)
					.buildAsync(URI.create(config.getServerUrl()), new WebSocket.Listener() {
						@Override
						public void onOpen(WebSocket webSocket) {
							LOGGER.info("Connected to Denorite Server");
							Denorite.webSocket = webSocket;
							DynamicCommandHandler.handleReconnect();
							WebSocket.Listener.super.onOpen(webSocket);
						}

						@Override
						public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
							handleIncomingMessage(data.toString());
							return WebSocket.Listener.super.onText(webSocket, data, last);
						}

						@Override
						public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
							LOGGER.warn("WebSocket closed: " + statusCode + " " + reason);
							handleDisconnect();
							return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
						}

						@Override
						public void onError(WebSocket webSocket, Throwable error) {
							LOGGER.error("WebSocket error: " + error.getMessage());
							handleDisconnect();
							WebSocket.Listener.super.onError(webSocket, error);
						}
					});
			try {
				ws.get(5, TimeUnit.SECONDS);
			} catch (Exception e) {
				LOGGER.error("Failed to connect to Denorite: " + e.getMessage());
				LOGGER.error("Please ensure the Denorite Server is running on " + config.getServerUrl());
				handleDisconnect();
			}
		} catch (Exception e) {
			LOGGER.error("Failed to initialize WebSocket: " + e.getMessage());
			handleDisconnect();
		}
	}

	private void handleDisconnect() {
		Denorite.webSocket = null;
		if (config.isStrictMode()) {
			LOGGER.error("WebSocket disconnected in strict mode. Shutting down server.");
			if (server != null) {
				server.stop(false);
			}
		} else {
			LOGGER.warn("WebSocket disconnected. Attempting to reconnect in " + (RECONNECT_DELAY / 1000) + " seconds.");
			CompletableFuture.delayedExecutor(RECONNECT_DELAY, TimeUnit.MILLISECONDS).execute(this::connectWebSocket);
		}
	}

	private void registerAllEvents() {
		registerServerEvents();
		registerPlayerEvents();
		registerEntityEvents();
		registerWorldEvents();
		registerBlockEvents();
		registerItemEvents();
		registerChatEvents();
	}

	public static void onContainerClose(ServerPlayerEntity player, ScreenHandler handler) {
		if (lastInteractedBlock != null && lastInteractedPos != null) {
			sendToTypeScript("container_interaction_end", serializeContainerInteraction(player, lastInteractedBlock, lastInteractedPos));
			lastInteractedBlock = null;
			lastInteractedPos = null;
		}
	}

	private boolean shouldBeLogged(Block block) {
		return block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.ENDER_CHEST
				|| block == Blocks.BARREL || block == Blocks.SHULKER_BOX;
	}

	private void registerServerEvents() {
		ServerLifecycleEvents.SERVER_STARTING.register(server -> sendToTypeScript("server_starting", null));
		ServerLifecycleEvents.SERVER_STARTED.register(server -> sendToTypeScript("server_started", null));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> sendToTypeScript("server_stopping", null));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> sendToTypeScript("server_stopped", null));

//		ServerTickEvents.START_SERVER_TICK.register((server) -> sendToTypeScript("server_tick_start", null));
//		ServerTickEvents.END_SERVER_TICK.register((server) -> sendToTypeScript("server_tick_end", null));
	}

	private void registerPlayerEvents() {
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			sendToTypeScript("player_respawned", serializePlayerRespawn(oldPlayer, newPlayer, alive));
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				sendToTypeScript("player_joined", serializePlayer(handler.getPlayer())));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				sendToTypeScript("player_left", serializePlayer(handler.getPlayer())));

		ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((world, entity, killedEntity) -> {
			if (killedEntity instanceof ServerPlayerEntity) {
				sendToTypeScript("player_death", serializePlayerDeath((ServerPlayerEntity) killedEntity, killedEntity.getRecentDamageSource()));
			} else {
				sendToTypeScript("entity_death", serializeEntityDeath(entity, killedEntity));
			}
		});

		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) ->
				sendToTypeScript("player_break_block_after", serializeBlockEvent((ServerPlayerEntity)player, pos, state)));

		AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
			sendToTypeScript("player_attack_block", serializeBlockEvent((ServerPlayerEntity)player, pos, world.getBlockState(pos)));
			return ActionResult.PASS;
		});

		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			sendToTypeScript("player_use_block", serializeBlockEvent((ServerPlayerEntity)player, hitResult.getBlockPos(), world.getBlockState(hitResult.getBlockPos())));
			return ActionResult.PASS;
		});

		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			Block block = world.getBlockState(hitResult.getBlockPos()).getBlock();
			if (!shouldBeLogged(block)) {
				return ActionResult.PASS;
			}

			if (player instanceof ServerPlayerEntity) {
				ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;
				BlockPos pos = hitResult.getBlockPos();
				lastInteractedBlock = block;
				lastInteractedPos = pos;
				sendToTypeScript("container_interaction_start", serializeContainerInteraction(serverPlayer, block, pos));
			}

			return ActionResult.PASS;
		});

		UseItemCallback.EVENT.register((player, world, hand) -> {
			sendToTypeScript("player_use_item", serializeItemEvent((ServerPlayerEntity)player, player.getStackInHand(hand)));
			return TypedActionResult.pass(player.getStackInHand(hand));
		});

		AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			sendToTypeScript("player_attack_entity", serializeEntityEvent((ServerPlayerEntity)player, entity));
			return ActionResult.PASS;
		});

		UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			sendToTypeScript("player_use_entity", serializeEntityEvent((ServerPlayerEntity)player, entity));
			return ActionResult.PASS;
		});
	}

	private void registerEntityEvents() {
		EntityElytraEvents.ALLOW.register((entity) -> {
			sendToTypeScript("entity_elytra_check", serializeEntity(entity));
			return true;
		});

		ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((world, entity, killedEntity) -> {
			if (killedEntity instanceof ServerPlayerEntity) {
				sendToTypeScript("player_death", serializePlayerDeath((ServerPlayerEntity) killedEntity, killedEntity.getRecentDamageSource()));
			} else if (killedEntity instanceof LivingEntity) {
				sendToTypeScript("entity_death", serializeEntityDeath(entity, (LivingEntity) killedEntity));
			}
		});

		ServerEntityWorldChangeEvents.AFTER_ENTITY_CHANGE_WORLD.register((originalEntity, newEntity, origin, destination) -> {
			sendToTypeScript("entity_changed_world", serializeEntityWorldChange(originalEntity, newEntity, origin, destination));
		});

		EntitySleepEvents.START_SLEEPING.register((entity, sleepingPos) -> {
			sendToTypeScript("entity_start_sleeping", serializeEntitySleep(entity, sleepingPos));
		});

		EntitySleepEvents.STOP_SLEEPING.register((entity, sleepingPos) -> {
			sendToTypeScript("entity_stop_sleeping", serializeEntitySleep(entity, sleepingPos));
		});
	}

	private void registerWorldEvents() {
//		ServerTickEvents.START_WORLD_TICK.register((world) ->
//				sendToTypeScript("world_tick_start", serializeWorld(world)));

//		ServerTickEvents.END_WORLD_TICK.register((world) ->
//				sendToTypeScript("world_tick_end", serializeWorld(world)));

		ServerWorldEvents.LOAD.register((server, world) ->
				sendToTypeScript("world_load", serializeWorld(world)));

		ServerWorldEvents.UNLOAD.register((server, world) ->
				sendToTypeScript("world_unload", serializeWorld(world)));
	}

	private void registerBlockEvents() {
		// Most block events are covered in player events, but you can add more specific ones here
	}

	private void registerItemEvents() {
		// Most item events are covered in player events, but you can add more specific ones here
	}

	private void registerChatEvents() {
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
			sendToTypeScript("player_chat", serializeChat(sender, message.getContent().getString()));
		});

		ServerMessageEvents.COMMAND_MESSAGE.register((message, source, params) -> {
			if (source.getPlayer() != null) {
				sendToTypeScript("player_command", serializeCommand(source.getPlayer(), String.valueOf(message)));
			}
		});
	}

	// Serialization methods

	private static JsonObject serializeContainerInteraction(ServerPlayerEntity player, Block block, BlockPos pos) {
		JsonObject data = new JsonObject();
		data.addProperty("playerId", player.getUuidAsString());
		data.addProperty("playerName", player.getName().getString());
		data.addProperty("blockType", block.toString());
		data.addProperty("x", pos.getX());
		data.addProperty("y", pos.getY());
		data.addProperty("z", pos.getZ());
		data.addProperty("dimension", player.getWorld().getRegistryKey().getValue().toString());
		return data;
	}

	private JsonObject serializePlayer(ServerPlayerEntity player) {
		JsonObject data = new JsonObject();
		data.addProperty("playerId", player.getUuidAsString());
		data.addProperty("playerName", player.getName().getString());
		data.addProperty("x", player.getX());
		data.addProperty("y", player.getY());
		data.addProperty("z", player.getZ());
		data.addProperty("dimension", player.getWorld().getRegistryKey().getValue().toString());
		return data;
	}

	private JsonObject serializePlayerDeath(ServerPlayerEntity player, DamageSource recentDamageSource) {
		JsonObject data = serializePlayer(player);
		DamageTracker damageTracker = player.getDamageTracker();
		Text deathMessage = damageTracker.getDeathMessage();

		if (deathMessage != null) {
			data.addProperty("deathMessage", deathMessage.getString());
		}

		Entity attacker = player.getAttacker();
		if (attacker != null) {
			data.addProperty("attackerId", attacker.getUuidAsString());
			data.addProperty("attackerType", attacker.getType().toString());
		}

		return data;
	}

	private JsonObject serializeEntityDeath(Entity killer, LivingEntity killedEntity) {
		JsonObject data = new JsonObject();
		data.add("killedEntity", serializeEntity(killedEntity));
		if (killer != null) {
			data.add("killer", serializeEntity(killer));
		}

		DamageTracker damageTracker = killedEntity.getDamageTracker();
		Text deathMessage = damageTracker.getDeathMessage();

		if (deathMessage != null) {
			data.addProperty("deathMessage", deathMessage.getString());
		}

		return data;
	}

	private JsonObject serializeWorld(World world) {
		JsonObject data = new JsonObject();
		data.addProperty("dimensionKey", world.getRegistryKey().getValue().toString());
		data.addProperty("time", world.getTime());
		data.addProperty("difficultyLevel", world.getDifficulty().getName());
		return data;
	}

	private JsonObject serializeChunk(World world, Chunk chunk) {
		JsonObject data = new JsonObject();
		data.addProperty("dimensionKey", world.getRegistryKey().getValue().toString());
		data.addProperty("chunkX", chunk.getPos().x);
		data.addProperty("chunkZ", chunk.getPos().z);
		return data;
	}

	private JsonObject serializeEntity(Entity entity) {
		JsonObject data = new JsonObject();
		data.addProperty("entityId", entity.getUuidAsString());
		data.addProperty("entityType", entity.getType().toString());
		data.addProperty("x", entity.getX());
		data.addProperty("y", entity.getY());
		data.addProperty("z", entity.getZ());
		return data;
	}

	private JsonObject serializeEntityKill(Entity killer, Entity victim) {
		JsonObject data = new JsonObject();
		data.add("killer", serializeEntity(killer));
		data.add("victim", serializeEntity(victim));
		return data;
	}

	private JsonObject serializeItemStack(ItemStack itemStack) {
		JsonObject data = new JsonObject();
		data.addProperty("item", itemStack.getItem().toString());
		data.addProperty("count", itemStack.getCount());
		data.addProperty("damage", itemStack.getDamage());
		return data;
	}

	private JsonObject serializeBlockEvent(ServerPlayerEntity player, BlockPos pos, BlockState state) {
		JsonObject data = new JsonObject();
		data.addProperty("playerId", player.getUuidAsString());
		data.addProperty("x", pos.getX());
		data.addProperty("y", pos.getY());
		data.addProperty("z", pos.getZ());
		data.addProperty("block", state.getBlock().toString());
		return data;
	}

	private JsonObject serializeEntityEvent(ServerPlayerEntity player, Entity entity) {
		JsonObject data = new JsonObject();
		data.addProperty("playerId", player.getUuidAsString());
		data.addProperty("entityId", entity.getUuidAsString());
		data.addProperty("entityType", entity.getType().toString());
		return data;
	}

	private JsonObject serializeItemEvent(ServerPlayerEntity player, ItemStack itemStack) {
		JsonObject data = new JsonObject();
		data.addProperty("playerId", player.getUuidAsString());
		data.addProperty("item", itemStack.getItem().toString());
		data.addProperty("count", itemStack.getCount());
		return data;
	}

	private JsonObject serializeChat(ServerPlayerEntity player, String message) {
		JsonObject data = new JsonObject();
		data.addProperty("playerId", player.getUuidAsString());
		data.addProperty("playerName", player.getName().getString());
		data.addProperty("message", message);
		return data;
	}

	private JsonObject serializeCommand(ServerPlayerEntity player, String command) {
		JsonObject data = new JsonObject();
		data.addProperty("playerId", player.getUuidAsString());
		data.addProperty("playerName", player.getName().getString());
		data.addProperty("command", command);
		return data;
	}

	private JsonObject serializeAdvancementCriterion(ServerPlayerEntity player, AdvancementEntry advancement, String criterion) {
		JsonObject data = new JsonObject();
		data.addProperty("playerId", player.getUuidAsString());
		data.addProperty("playerName", player.getName().getString());
		data.addProperty("advancementId", advancement.id().toString());
		data.addProperty("criterion", criterion);
		return data;
	}

	private JsonObject serializePlayerRespawn(ServerPlayerEntity oldPlayer, ServerPlayerEntity newPlayer, boolean alive) {
		JsonObject data = new JsonObject();
		data.addProperty("playerId", newPlayer.getUuidAsString());
		data.addProperty("playerName", newPlayer.getName().getString());
		data.addProperty("alive", alive);
		data.addProperty("x", newPlayer.getX());
		data.addProperty("y", newPlayer.getY());
		data.addProperty("z", newPlayer.getZ());
		data.addProperty("dimension", newPlayer.getWorld().getRegistryKey().getValue().toString());
		return data;
	}

	private JsonObject serializeEntityWorldChange(Entity originalEntity, Entity newEntity, ServerWorld origin, ServerWorld destination) {
		JsonObject data = new JsonObject();
		data.addProperty("entityId", newEntity.getUuidAsString());
		data.addProperty("entityType", newEntity.getType().toString());
		data.addProperty("originalWorld", origin.getRegistryKey().getValue().toString());
		data.addProperty("newWorld", destination.getRegistryKey().getValue().toString());
		data.addProperty("x", newEntity.getX());
		data.addProperty("y", newEntity.getY());
		data.addProperty("z", newEntity.getZ());
		return data;
	}

	private JsonObject serializeEntitySleep(Entity entity, BlockPos sleepingPos) {
		JsonObject data = new JsonObject();
		data.addProperty("entityId", entity.getUuidAsString());
		data.addProperty("entityType", entity.getType().toString());
		data.addProperty("x", sleepingPos.getX());
		data.addProperty("y", sleepingPos.getY());
		data.addProperty("z", sleepingPos.getZ());
		data.addProperty("dimension", entity.getWorld().getRegistryKey().getValue().toString());
		return data;
	}

	public static void sendToTypeScript(String eventType, JsonObject data) {
		if (webSocket != null) {
			JsonObject jsonMessage = new JsonObject();
			jsonMessage.addProperty("eventType", eventType);
			jsonMessage.add("data", data);
			String message = jsonMessage.toString();
			LOGGER.info("← " + message);
			webSocket.sendText(message, true);
		} else {
			LOGGER.warn("WebSocket is null, cannot send message to Denorite: " + eventType);
		}
	}

	private void handleIncomingMessage(String message) {
		try {
			JsonObject jsonMessage = gson.fromJson(message, JsonObject.class);
			String id = jsonMessage.get("id").getAsString();
			String type = jsonMessage.get("type").getAsString();
			JsonElement dataElement = jsonMessage.get("data");

			JsonObject response = new JsonObject();
			response.addProperty("id", id);

			LOGGER.info("→ " + message);

			try {
				String result = "";
				switch (type) {
					case "command":
						result = executeCommand(dataElement.getAsString());
						break;
					case "chat":
						broadcastMessage(dataElement.getAsString());
						result = "Message broadcasted";
						break;
					case "register_command":
						DynamicCommandHandler.confirmReconnect();
						DynamicCommandHandler.registerCommand(dataElement.getAsJsonObject());
						result = "Command registered. Restart the server to apply changes.";
						break;
					case "unregister_command":
						DynamicCommandHandler.unregisterCommand(dataElement.getAsString());
						result = "Command unregistered. Restart the server to apply changes.";
						break;
					case "clear_commands":
						DynamicCommandHandler.clearCommands();
						result = "All custom commands cleared. Restart the server to apply changes.";
						break;
					default:
						LOGGER.info("Unknown message type: " + type);
						response.addProperty("error", "Unknown message type");
						result = "Error: Unknown message type";
				}
				response.addProperty("result", result);
				LOGGER.info("Command executed. Result: " + result);
			} catch (Exception e) {
				LOGGER.error("Error executing command: " + e.getMessage());
				response.addProperty("error", e.getMessage());
			}

			if (webSocket != null) {
				String responseString = response.toString();
				LOGGER.info("Sending response: " + responseString);
				webSocket.sendText(responseString, true);
			}
		} catch (Exception e) {
			LOGGER.error("Error handling incoming message: " + e.getMessage());
		}
	}

	private String executeCommand(String command) {
		if (server != null) {
			try {
				StringBuilder output = new StringBuilder();
				ServerCommandSource source = server.getCommandSource().withOutput(new CommandOutput() {
					@Override
					public void sendMessage(Text message) {
						output.append(message.getString()).append("\n");
					}

					@Override
					public boolean shouldReceiveFeedback() {
						return true;
					}

					@Override
					public boolean shouldTrackOutput() {
						return true;
					}

					@Override
					public boolean shouldBroadcastConsoleToOps() {
						return false;
					}
				});

				server.getCommandManager().executeWithPrefix(source, command);
				return output.toString().trim();
			} catch (Exception e) {
				LOGGER.error("Error executing command: " + e.getMessage());
				return "Error: " + e.getMessage();
			}
		}
		return "Error: Server is null";
	}

	private void broadcastMessage(String message) {
		if (server != null) {
			server.getPlayerManager().broadcast(Text.of(message), false);
		}
	}
}
