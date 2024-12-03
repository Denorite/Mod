package com.denorite;

import com.google.gson.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.*;
import net.minecraft.util.Identifier;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.advancement.AdvancementProgress;
import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.server.network.ServerPlayerEntity;

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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageTracker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

public class Denorite implements ModInitializer {
	public static final String MOD_ID = "Denorite";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static final StringBuffer messageBuffer = new StringBuffer();
	private static final Object bufferLock = new Object();

	private static WebSocket webSocket;
	private static final Gson gson = new Gson();
	static MinecraftServer server;
	private static boolean strictMode = true;
	private static final int RECONNECT_DELAY = 1000;

	private DenoriteConfig config;

	private static Block lastInteractedBlock = null;
	private static BlockPos lastInteractedPos = null;

	@Override
	public void onInitialize() {
		LOGGER.info("Initializing Denorite");
//		DenoriteBanner.printBanner();
		config = new DenoriteConfig();
		initializeWebSocket();
		DynamicCommandHandler.initialize();
		FileSystemHandler.initialize();
		BlueMapIntegration.initialize();
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
							DenoriteBanner.printBanner();
							Denorite.webSocket = webSocket;
							DynamicCommandHandler.handleReconnect();
							WebSocket.Listener.super.onOpen(webSocket);
						}

						@Override
						public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
							synchronized (bufferLock) {
								messageBuffer.append(data);

								if (last) {
									try {
										String completeMessage = messageBuffer.toString();
										handleIncomingMessage(completeMessage);
									} catch (Exception e) {
										LOGGER.error("Error processing complete message: " + e.getMessage());
										LOGGER.error("Message content: " + messageBuffer.toString());
									} finally {
										messageBuffer.setLength(0); // Clear the buffer
									}
								}
							}
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
		registerChatEvents();
		registerProjectileEvents();
		registerAdvancementEvents();
		registerExperienceEvents();
		registerTradeEvents();
		registerWeatherEvents();
		registerRedstoneEvents();
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


	public static void sendToTypeScript(String eventType, JsonObject data) {
		if (webSocket != null) {
			JsonObject jsonMessage = new JsonObject();
			jsonMessage.addProperty("eventType", eventType);
			jsonMessage.add("data", data);
			String message = jsonMessage.toString();
			webSocket.sendText(message, true);
		} else {
			// LOGGER.warn("WebSocket is null, cannot send message to Denorite: " + eventType);
		}
	}

	private void handleIncomingMessage(String message) {
		if (message == null || message.trim().isEmpty()) {
			LOGGER.warn("Received empty message");
			return;
		}

		try {
			JsonObject jsonMessage = gson.fromJson(message, JsonObject.class);
			if (!jsonMessage.has("id") || !jsonMessage.has("type")) {
				LOGGER.warn("Received malformed message without required fields: " + message);
				return;
			}

			String id = jsonMessage.get("id").getAsString();
			String type = jsonMessage.get("type").getAsString();
			JsonElement dataElement = jsonMessage.get("data");

			JsonObject response = new JsonObject();
			response.addProperty("id", id);

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
					case "bluemap":
						BlueMapIntegration.handleMarkerCommand(dataElement.getAsJsonObject());
						result = "Bluemap marker command executed";
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
					case "files":
						result = FileSystemHandler.handleFileCommand(
								dataElement.getAsJsonObject().get("subcommand").getAsString(),
								dataElement.getAsJsonObject().get("arguments").getAsJsonObject()
						).toString();
						break;
					default:
						LOGGER.info("Unknown message type: " + type);
						response.addProperty("error", "Unknown message type");
						result = "Error: Unknown message type";
				}
				response.addProperty("result", result);
			} catch (Exception e) {
				LOGGER.error("Error executing command: " + e.getMessage());
				response.addProperty("error", e.getMessage());
			}

			if (webSocket != null) {
				String responseString = response.toString();
//				LOGGER.info("← " + responseString);
				webSocket.sendText(responseString, true);
			}
		} catch (JsonSyntaxException e) {
			LOGGER.error("JSON parsing error: " + e.getMessage());
			LOGGER.error("Problematic message: " + message);
		} catch (Exception e) {
			LOGGER.error("Error handling message: " + e.getMessage());
			LOGGER.error("Message content: " + message);
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

				LOGGER.info(command);

				return output.toString().trim();
			} catch (Exception e) {
				LOGGER.error("Error executing command: " + e.getMessage());
				return "Error: " + e.getMessage();
			}
		}
		return "Error: Server is null";
	}

	/**
	 * Execute a command using ServerWorld's command execution
	 */
	public static void executeWorldCommand(ServerWorld world, BlockPos pos, String command) {
		world.getServer().getCommandManager().executeWithPrefix(
				createCommandSource(world, pos),
				command
		);
	}

	/**
	 * Create a command source at a specific position
	 */
	private static ServerCommandSource createCommandSource(ServerWorld world, BlockPos pos) {
		return new ServerCommandSource(
				world.getServer(),
				new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5),
				Vec2f.ZERO,
				world,
				2, // Command level (2 is for command blocks)
				"CommandBlock",
				Text.literal("CommandBlock"),
				world.getServer(),
				null
		);
	}

	private void broadcastMessage(String message) {
		if (server != null) {
			server.getPlayerManager().broadcast(Text.of(message), false);
		}
	}

	private void registerServerEvents() {
		ServerLifecycleEvents.SERVER_STARTING.register(server -> sendToTypeScript("server_starting", null));
		ServerLifecycleEvents.SERVER_STARTED.register(server -> sendToTypeScript("server_started", null));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> sendToTypeScript("server_stopping", null));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> sendToTypeScript("server_stopped", null));
		ServerTickEvents.START_SERVER_TICK.register((server) -> sendToTypeScript("server_tick_start", null));
		ServerTickEvents.END_SERVER_TICK.register((server) -> sendToTypeScript("server_tick_end", null));

		ServerLifecycleEvents.BEFORE_SAVE.register((server, srt, str) ->
				sendToTypeScript("server_before_save", null));

		ServerLifecycleEvents.AFTER_SAVE.register((server, srt, str) ->
				sendToTypeScript("server_after_save", null));

		ServerLifecycleEvents.START_DATA_PACK_RELOAD.register((server, resourceManager) ->
				sendToTypeScript("data_pack_reload_start", null));

		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
			JsonObject data = new JsonObject();
			data.addProperty("success", success);
			sendToTypeScript("data_pack_reload_end", data);
		});

		ServerLifecycleEvents.SYNC_DATA_PACK_CONTENTS.register((player, joined) -> {
			JsonObject data = new JsonObject();
			data.addProperty("playerId", player.getUuidAsString());
			data.addProperty("playerName", player.getName().getString());
			data.addProperty("joined", joined);
			sendToTypeScript("data_pack_sync", data);
		});
;	}

	private void registerPlayerEvents() {
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			sendToTypeScript("player_respawned", serializePlayerRespawn(oldPlayer, newPlayer, alive));
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				sendToTypeScript("player_joined", serializePlayer(handler.getPlayer())));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				sendToTypeScript("player_left", serializePlayer(handler.getPlayer())));

		ServerPlayConnectionEvents.INIT.register((handler, server) -> {
			JsonObject data = new JsonObject();
			data.addProperty("playerId", handler.player.getUuidAsString());
			data.addProperty("playerName", handler.player.getName().getString());
			sendToTypeScript("player_connection_init", data);
		});

		PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
			sendToTypeScript("player_break_block_before", serializeBlockEvent((ServerPlayerEntity)player, pos, state));
			return true;
		});

		PlayerBlockBreakEvents.CANCELED.register((world, player, pos, state, blockEntity) -> {
			sendToTypeScript("player_break_block_canceled", serializeBlockEvent((ServerPlayerEntity)player, pos, state));
		});

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

			if (player instanceof ServerPlayerEntity serverPlayer) {
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
		ServerTickEvents.START_WORLD_TICK.register((world) ->
				sendToTypeScript("world_tick_start", serializeWorld(world)));

		ServerTickEvents.END_WORLD_TICK.register((world) ->
				sendToTypeScript("world_tick_end", serializeWorld(world)));

		ServerWorldEvents.LOAD.register((server, world) ->
				sendToTypeScript("world_load", serializeWorld(world)));

		ServerWorldEvents.UNLOAD.register((server, world) ->
				sendToTypeScript("world_unload", serializeWorld(world)));
	}

	private void registerChatEvents() {
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
			sendToTypeScript("player_chat", serializeChat(sender, message.getContent().getString()));
		});

		ServerMessageEvents.COMMAND_MESSAGE.register((message, sender, params) -> {
			JsonObject data = new JsonObject();
			if (sender.getPlayer() != null) {
				data.addProperty("playerId", sender.getPlayer().getUuidAsString());
				data.addProperty("playerName", sender.getPlayer().getName().getString());
			}
			data.addProperty("message", message.getContent().getString());
			sendToTypeScript("command_message", data);
		});

//		ServerMessageEvents.GAME_MESSAGE.register((message, overlay) -> {
//			if (message instanceof Text text) {
//				JsonObject data = new JsonObject();
//				data.addProperty("message", text.getString());
//				sendToTypeScript("game_message", data);
//			}
//		});
	}

	private void registerProjectileEvents() {
		// Track arrow hits and other projectiles
		ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((world, entity, killed) -> {
			if (entity instanceof ProjectileEntity projectile) {
				Entity owner = projectile.getOwner();
				JsonObject data = new JsonObject();
				data.addProperty("projectileType", projectile.getType().toString());
				if (owner != null) {
					data.addProperty("ownerId", owner.getUuidAsString());
					data.addProperty("ownerType", owner.getType().toString());
				}
				data.add("target", serializeEntity(killed));
				sendToTypeScript("projectile_kill", data);
			}
		});
	}


	private void registerAdvancementEvents() {
		// Track advancement progress
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			for (AdvancementEntry advancement : server.getAdvancementLoader().getAdvancements()) {
				AdvancementProgress progress = newPlayer.getAdvancementTracker().getProgress(advancement);
				if (progress.isDone()) {
					sendToTypeScript("advancement_complete", serializeAdvancement(newPlayer, advancement));
				}
			}
		});
	}

	private void registerExperienceEvents() {
		// Track XP changes
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
				int currentXp = player.experienceLevel;
				int currentProgress = (int)(player.experienceProgress * 100);
				JsonObject data = new JsonObject();
				data.addProperty("playerId", player.getUuidAsString());
				data.addProperty("level", currentXp);
				data.addProperty("progress", currentProgress);
				sendToTypeScript("experience_update", data);
			}
		});
	}

	private void registerTradeEvents() {
		// Track villager trades
		UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (entity instanceof net.minecraft.village.Merchant) {
				JsonObject data = new JsonObject();
				data.addProperty("playerId", player.getUuidAsString());
				data.addProperty("merchantId", entity.getUuidAsString());
				data.addProperty("merchantType", entity.getType().toString());
				sendToTypeScript("merchant_interaction", data);
			}
			return ActionResult.PASS;
		});
	}

	private void registerWeatherEvents() {
		// Track weather changes
		ServerTickEvents.START_WORLD_TICK.register(world -> {
			JsonObject data = new JsonObject();
			data.addProperty("dimension", world.getRegistryKey().getValue().toString());
			data.addProperty("isRaining", world.isRaining());
			data.addProperty("isThundering", world.isThundering());
			data.addProperty("rainGradient", world.getRainGradient(1.0F));
			sendToTypeScript("weather_update", data);
		});
	}

	private void registerRedstoneEvents() {
		// Track redstone signal changes
		AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
			BlockState state = world.getBlockState(pos);
			if (state.getBlock() instanceof RedstoneWireBlock ||
					state.getBlock() instanceof AbstractRedstoneGateBlock) {
				JsonObject data = new JsonObject();
				data.addProperty("x", pos.getX());
				data.addProperty("y", pos.getY());
				data.addProperty("z", pos.getZ());
				data.addProperty("power", state.get(RedstoneWireBlock.POWER));
				sendToTypeScript("redstone_update", data);
			}
			return ActionResult.PASS;
		});
	}

	// New serialization helpers
	private JsonObject serializeAdvancement(ServerPlayerEntity player, AdvancementEntry advancement) {
		JsonObject data = new JsonObject();
		data.addProperty("playerId", player.getUuidAsString());
		data.addProperty("playerName", player.getName().getString());
		data.addProperty("advancementId", advancement.id().toString());
		data.addProperty("title", advancement.value().display().toString());
		return data;
	}


	private String serializeText(Text text) {
		return text != null ? text.getString() : "";
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

	private JsonObject serializeCommandMessage(Text message, ServerCommandSource source) {
		JsonObject data = new JsonObject();
		ServerPlayerEntity player = source.getPlayer();
		if (player != null) {
			data.addProperty("playerId", player.getUuidAsString());
			data.addProperty("playerName", player.getName().getString());
		}
		data.addProperty("message", message.getString());
		data.addProperty("command", source.getName());
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

	private JsonObject serializeScreenInteraction(ServerPlayerEntity player) {
		JsonObject data = new JsonObject();
		data.addProperty("playerId", player.getUuidAsString());
		data.addProperty("playerName", player.getName().getString());
		data.addProperty("currentScreen", player.currentScreenHandler.getClass().getSimpleName());
		return data;
	}

	private JsonObject serializeInventoryChange(ServerPlayerEntity player) {
		JsonObject data = new JsonObject();
		data.addProperty("playerId", player.getUuidAsString());
		data.addProperty("playerName", player.getName().getString());

		JsonArray inventory = new JsonArray();
		for (int i = 0; i < player.getInventory().size(); i++) {
			ItemStack stack = player.getInventory().getStack(i);
			if (!stack.isEmpty()) {
				JsonObject item = serializeItemStack(stack);
				item.addProperty("slot", i);
				inventory.add(item);
			}
		}
		data.add("inventory", inventory);

		return data;
	}

}
