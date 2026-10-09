# Fabric API 事件索引（自动生成）

来源：本机 Gradle 缓存中 Fabric API 0.140.2+1.21.11 的全部 `-sources.jar`（共 46 个源码包）。

用途：阶段 4~7 判断某个游戏行为**有没有现成事件**，从而决定用事件还是写 Mixin（需求文档禁止臆造 API）。

共 146 个事件。

| 模块 | 事件类 | 事件字段 | 回调签名 |
|---|---|---|---|
| `fabric-entity-events-v1-3.0.5+4ebb5c083e` | `EntityElytraEvents` | `ALLOW` | `allowElytraFlight(class_1309 entity)` |
| `fabric-entity-events-v1-3.0.5+4ebb5c083e` | `EntityElytraEvents` | `CUSTOM` | `useCustomElytra(class_1309 entity, boolean tickElytra)` |
| `fabric-entity-events-v1-3.0.5+4ebb5c083e` | `EntitySleepEvents` | `ALLOW_BED` | `(未解析)` |
| `fabric-entity-events-v1-3.0.5+4ebb5c083e` | `EntitySleepEvents` | `ALLOW_NEARBY_MONSTERS` | `(未解析)` |
| `fabric-entity-events-v1-3.0.5+4ebb5c083e` | `EntitySleepEvents` | `ALLOW_RESETTING_TIME` | `allowResettingTime(class_1657 player)` |
| `fabric-entity-events-v1-3.0.5+4ebb5c083e` | `EntitySleepEvents` | `ALLOW_SETTING_SPAWN` | `allowSettingSpawn(class_1657 player, class_2338 sleepingPos)` |
| `fabric-entity-events-v1-3.0.5+4ebb5c083e` | `EntitySleepEvents` | `ALLOW_SLEEPING` | `(未解析)` |
| `fabric-entity-events-v1-3.0.5+4ebb5c083e` | `EntitySleepEvents` | `MODIFY_SLEEPING_DIRECTION` | `(未解析)` |
| `fabric-entity-events-v1-3.0.5+4ebb5c083e` | `EntitySleepEvents` | `MODIFY_WAKE_UP_POSITION` | `(未解析)` |
| `fabric-entity-events-v1-3.0.5+4ebb5c083e` | `EntitySleepEvents` | `SET_BED_OCCUPATION_STATE` | `setBedOccupationState(class_1309 entity, class_2338 sleepingPos, class_2680 bedState, boolean occupied)` |
| `fabric-entity-events-v1-3.0.5+4ebb5c083e` | `EntitySleepEvents` | `START_SLEEPING` | `onStartSleeping(class_1309 entity, class_2338 sleepingPos)` |
| `fabric-entity-events-v1-3.0.5+4ebb5c083e` | `EntitySleepEvents` | `STOP_SLEEPING` | `onStopSleeping(class_1309 entity, class_2338 sleepingPos)` |
| `fabric-entity-events-v1-3.0.5+4ebb5c083e` | `ServerEntityCombatEvents` | `AFTER_KILLED_OTHER_ENTITY` | `afterKilledOtherEntity(class_3218 world, class_1297 entity, class_1309 killedEntity, class_1282 damageSource)` |
| `fabric-entity-events-v1-3.0.5+4ebb5c083e` | `ServerEntityWorldChangeEvents` | `AFTER_ENTITY_CHANGE_WORLD` | `afterChangeWorld(class_1297 originalEntity, class_1297 newEntity, class_3218 origin, class_3218 destination)` |
| `fabric-entity-events-v1-3.0.5+4ebb5c083e` | `ServerEntityWorldChangeEvents` | `AFTER_PLAYER_CHANGE_WORLD` | `afterChangeWorld(class_3222 player, class_3218 origin, class_3218 destination)` |
| `fabric-entity-events-v1-3.0.5+4ebb5c083e` | `ServerLivingEntityEvents` | `AFTER_DAMAGE` | `afterDamage(class_1309 entity, class_1282 source, float baseDamageTaken, float damageTaken, boolean blocked)` |
| `fabric-entity-events-v1-3.0.5+4ebb5c083e` | `ServerLivingEntityEvents` | `AFTER_DEATH` | `afterDeath(class_1309 entity, class_1282 damageSource)` |
| `fabric-entity-events-v1-3.0.5+4ebb5c083e` | `ServerLivingEntityEvents` | `ALLOW_DAMAGE` | `allowDamage(class_1309 entity, class_1282 source, float amount)` |
| `fabric-entity-events-v1-3.0.5+4ebb5c083e` | `ServerLivingEntityEvents` | `ALLOW_DEATH` | `allowDeath(class_1309 entity, class_1282 damageSource, float damageAmount)` |
| `fabric-entity-events-v1-3.0.5+4ebb5c083e` | `ServerLivingEntityEvents` | `MOB_CONVERSION` | `onConversion(class_1308 previous, class_1308 converted, class_10179 conversionContext)` |
| `fabric-entity-events-v1-3.0.5+4ebb5c083e` | `ServerPlayerEvents` | `AFTER_RESPAWN` | `afterRespawn(class_3222 oldPlayer, class_3222 newPlayer, boolean alive)` |
| `fabric-entity-events-v1-3.0.5+4ebb5c083e` | `ServerPlayerEvents` | `ALLOW_DEATH` | `allowDeath(class_3222 player, class_1282 damageSource, float damageAmount)` |
| `fabric-entity-events-v1-3.0.5+4ebb5c083e` | `ServerPlayerEvents` | `COPY_FROM` | `copyFromPlayer(class_3222 oldPlayer, class_3222 newPlayer, boolean alive)` |
| `fabric-entity-events-v1-3.0.5+4ebb5c083e` | `ServerPlayerEvents` | `JOIN` | `onJoin(class_3222 player)` |
| `fabric-entity-events-v1-3.0.5+4ebb5c083e` | `ServerPlayerEvents` | `LEAVE` | `onLeave(class_3222 player)` |
| `fabric-events-interaction-v0-4.0.44+1fb1cde93e` | `ClientPlayerBlockBreakEvents` | `AFTER` | `afterBlockBreak(class_638 world, class_746 player, class_2338 pos, class_2680 state)` |
| `fabric-events-interaction-v0-4.0.44+1fb1cde93e` | `PlayerBlockBreakEvents` | `AFTER` | `afterBlockBreak(class_1937 world, class_1657 player, class_2338 pos, class_2680 state, @Nullable class_2586 blockEntity)` |
| `fabric-events-interaction-v0-4.0.44+1fb1cde93e` | `PlayerBlockBreakEvents` | `BEFORE` | `beforeBlockBreak(class_1937 world, class_1657 player, class_2338 pos, class_2680 state, @Nullable class_2586 blockEntity)` |
| `fabric-events-interaction-v0-4.0.44+1fb1cde93e` | `PlayerBlockBreakEvents` | `CANCELED` | `onBlockBreakCanceled(class_1937 world, class_1657 player, class_2338 pos, class_2680 state, @Nullable class_2586 blockEntity)` |
| `fabric-events-interaction-v0-4.0.44+1fb1cde93e` | `PlayerPickItemEvents` | `BLOCK` | `(未解析)` |
| `fabric-events-interaction-v0-4.0.44+1fb1cde93e` | `PlayerPickItemEvents` | `ENTITY` | `(未解析)` |
| `fabric-item-api-v1-11.5.20+d0c46b9e3e` | `DefaultItemComponentEvents` | `MODIFY` | `modify(ModifyContext context)` |
| `fabric-item-api-v1-11.5.20+d0c46b9e3e` | `EnchantmentEvents` | `ALLOW_ENCHANTING` | `(未解析)` |
| `fabric-item-api-v1-11.5.20+d0c46b9e3e` | `EnchantmentEvents` | `MODIFY` | `(未解析)` |
| `fabric-item-group-api-v1-4.2.36+4fc5413f3e` | `ItemGroupEvents` | `MODIFY_ENTRIES_ALL` | `modifyEntries(class_1761 group, FabricItemGroupEntries entries)` |
| `fabric-lifecycle-events-v1-2.6.15+4ebb5c083e` | `ClientBlockEntityEvents` | `BLOCK_ENTITY_LOAD` | `onLoad(class_2586 blockEntity, class_638 world)` |
| `fabric-lifecycle-events-v1-2.6.15+4ebb5c083e` | `ClientBlockEntityEvents` | `BLOCK_ENTITY_UNLOAD` | `onUnload(class_2586 blockEntity, class_638 world)` |
| `fabric-lifecycle-events-v1-2.6.15+4ebb5c083e` | `ClientChunkEvents` | `CHUNK_LOAD` | `onChunkLoad(class_638 world, class_2818 chunk)` |
| `fabric-lifecycle-events-v1-2.6.15+4ebb5c083e` | `ClientChunkEvents` | `CHUNK_UNLOAD` | `onChunkUnload(class_638 world, class_2818 chunk)` |
| `fabric-lifecycle-events-v1-2.6.15+4ebb5c083e` | `ClientEntityEvents` | `ENTITY_LOAD` | `onLoad(class_1297 entity, class_638 world)` |
| `fabric-lifecycle-events-v1-2.6.15+4ebb5c083e` | `ClientEntityEvents` | `ENTITY_UNLOAD` | `onUnload(class_1297 entity, class_638 world)` |
| `fabric-lifecycle-events-v1-2.6.15+4ebb5c083e` | `ClientLifecycleEvents` | `CLIENT_STARTED` | `onClientStarted(class_310 client)` |
| `fabric-lifecycle-events-v1-2.6.15+4ebb5c083e` | `ClientLifecycleEvents` | `CLIENT_STOPPING` | `onClientStopping(class_310 client)` |
| `fabric-lifecycle-events-v1-2.6.15+4ebb5c083e` | `ClientTickEvents` | `END_CLIENT_TICK` | `onEndTick(class_310 client)` |
| `fabric-lifecycle-events-v1-2.6.15+4ebb5c083e` | `ClientTickEvents` | `END_WORLD_TICK` | `onEndTick(class_638 world)` |
| `fabric-lifecycle-events-v1-2.6.15+4ebb5c083e` | `ClientTickEvents` | `START_CLIENT_TICK` | `onStartTick(class_310 client)` |
| `fabric-lifecycle-events-v1-2.6.15+4ebb5c083e` | `ClientTickEvents` | `START_WORLD_TICK` | `onStartTick(class_638 world)` |
| `fabric-lifecycle-events-v1-2.6.15+4ebb5c083e` | `ClientWorldEvents` | `AFTER_CLIENT_WORLD_CHANGE` | `afterWorldChange(class_310 client, class_638 world)` |
| `fabric-lifecycle-events-v1-2.6.15+4ebb5c083e` | `CommonLifecycleEvents` | `TAGS_LOADED` | `onTagsLoaded(class_5455 registries, boolean client)` |
| `fabric-lifecycle-events-v1-2.6.15+4ebb5c083e` | `ServerBlockEntityEvents` | `BLOCK_ENTITY_LOAD` | `onLoad(class_2586 blockEntity, class_3218 world)` |
| `fabric-lifecycle-events-v1-2.6.15+4ebb5c083e` | `ServerBlockEntityEvents` | `BLOCK_ENTITY_UNLOAD` | `onUnload(class_2586 blockEntity, class_3218 world)` |
| `fabric-lifecycle-events-v1-2.6.15+4ebb5c083e` | `ServerChunkEvents` | `CHUNK_GENERATE` | `onChunkGenerate(class_3218 world, class_2818 chunk)` |
| `fabric-lifecycle-events-v1-2.6.15+4ebb5c083e` | `ServerChunkEvents` | `CHUNK_LEVEL_TYPE_CHANGE` | `onChunkLevelTypeChange(class_3218 world, class_2818 chunk, class_3194 oldLevelType, class_3194 newLevelType)` |
| `fabric-lifecycle-events-v1-2.6.15+4ebb5c083e` | `ServerChunkEvents` | `CHUNK_LOAD` | `onChunkLoad(class_3218 world, class_2818 chunk)` |
| `fabric-lifecycle-events-v1-2.6.15+4ebb5c083e` | `ServerChunkEvents` | `CHUNK_UNLOAD` | `onChunkUnload(class_3218 world, class_2818 chunk)` |
| `fabric-lifecycle-events-v1-2.6.15+4ebb5c083e` | `ServerEntityEvents` | `ENTITY_LOAD` | `onLoad(class_1297 entity, class_3218 world)` |
| `fabric-lifecycle-events-v1-2.6.15+4ebb5c083e` | `ServerEntityEvents` | `ENTITY_UNLOAD` | `onUnload(class_1297 entity, class_3218 world)` |
| `fabric-lifecycle-events-v1-2.6.15+4ebb5c083e` | `ServerEntityEvents` | `EQUIPMENT_CHANGE` | `onChange(class_1309 livingEntity, class_1304 equipmentSlot, class_1799 previousStack, class_1799 currentStack)` |
| `fabric-lifecycle-events-v1-2.6.15+4ebb5c083e` | `ServerLifecycleEvents` | `AFTER_SAVE` | `onAfterSave(MinecraftServer server, boolean flush, boolean force)` |
| `fabric-lifecycle-events-v1-2.6.15+4ebb5c083e` | `ServerLifecycleEvents` | `BEFORE_SAVE` | `onBeforeSave(MinecraftServer server, boolean flush, boolean force)` |
| `fabric-lifecycle-events-v1-2.6.15+4ebb5c083e` | `ServerLifecycleEvents` | `END_DATA_PACK_RELOAD` | `endDataPackReload(MinecraftServer server, class_6860 resourceManager, boolean success)` |
| `fabric-lifecycle-events-v1-2.6.15+4ebb5c083e` | `ServerLifecycleEvents` | `SERVER_STARTED` | `onServerStarted(MinecraftServer server)` |
| `fabric-lifecycle-events-v1-2.6.15+4ebb5c083e` | `ServerLifecycleEvents` | `SERVER_STARTING` | `onServerStarting(MinecraftServer server)` |
| `fabric-lifecycle-events-v1-2.6.15+4ebb5c083e` | `ServerLifecycleEvents` | `SERVER_STOPPED` | `onServerStopped(MinecraftServer server)` |
| `fabric-lifecycle-events-v1-2.6.15+4ebb5c083e` | `ServerLifecycleEvents` | `SERVER_STOPPING` | `onServerStopping(MinecraftServer server)` |
| `fabric-lifecycle-events-v1-2.6.15+4ebb5c083e` | `ServerLifecycleEvents` | `START_DATA_PACK_RELOAD` | `startDataPackReload(MinecraftServer server, class_6860 resourceManager)` |
| `fabric-lifecycle-events-v1-2.6.15+4ebb5c083e` | `ServerLifecycleEvents` | `SYNC_DATA_PACK_CONTENTS` | `onSyncDataPackContents(class_3222 player, boolean joined)` |
| `fabric-lifecycle-events-v1-2.6.15+4ebb5c083e` | `ServerTickEvents` | `END_SERVER_TICK` | `onEndTick(MinecraftServer server)` |
| `fabric-lifecycle-events-v1-2.6.15+4ebb5c083e` | `ServerTickEvents` | `END_WORLD_TICK` | `onEndTick(class_3218 world)` |
| `fabric-lifecycle-events-v1-2.6.15+4ebb5c083e` | `ServerTickEvents` | `START_SERVER_TICK` | `onStartTick(MinecraftServer server)` |
| `fabric-lifecycle-events-v1-2.6.15+4ebb5c083e` | `ServerTickEvents` | `START_WORLD_TICK` | `onStartTick(class_3218 world)` |
| `fabric-lifecycle-events-v1-2.6.15+4ebb5c083e` | `ServerWorldEvents` | `LOAD` | `onWorldLoad(MinecraftServer server, class_3218 world)` |
| `fabric-lifecycle-events-v1-2.6.15+4ebb5c083e` | `ServerWorldEvents` | `UNLOAD` | `onWorldUnload(MinecraftServer server, class_3218 world)` |
| `fabric-loot-api-v2-3.0.73+3f89f5a53e` | `LootTableEvents` | `ALL_LOADED` | `onLootTablesLoaded(class_3300 resourceManager, class_2378<class_52> lootRegistry)` |
| `fabric-loot-api-v2-3.0.73+3f89f5a53e` | `LootTableEvents` | `MODIFY` | `modifyLootTable(class_5321<class_52> key, class_52.class_53 tableBuilder, LootTableSource source)` |
| `fabric-loot-api-v2-3.0.73+3f89f5a53e` | `LootTableEvents` | `REPLACE` | `(未解析)` |
| `fabric-loot-api-v3-2.0.20+78c8b4663e` | `LootTableEvents` | `ALL_LOADED` | `onLootTablesLoaded(class_3300 resourceManager, class_2378<class_52> lootRegistry)` |
| `fabric-loot-api-v3-2.0.20+78c8b4663e` | `LootTableEvents` | `MODIFY` | `modifyLootTable(class_5321<class_52> key, class_52.class_53 tableBuilder, LootTableSource source, class_7225.class_7874 registries)` |
| `fabric-loot-api-v3-2.0.20+78c8b4663e` | `LootTableEvents` | `MODIFY_DROPS` | `modifyLootTableDrops(class_6880<class_52> entry, class_47 context, List<class_1799> drops)` |
| `fabric-loot-api-v3-2.0.20+78c8b4663e` | `LootTableEvents` | `REPLACE` | `(未解析)` |
| `fabric-message-api-v1-6.1.12+4ebb5c083e` | `ClientReceiveMessageEvents` | `ALLOW_CHAT` | `allowReceiveChatMessage(class_2561 message, @Nullable class_7471 signedMessage, @Nullable GameProfile sender, class_2556.class_7602 params, Instant receptionTimestamp)` |
| `fabric-message-api-v1-6.1.12+4ebb5c083e` | `ClientReceiveMessageEvents` | `ALLOW_GAME` | `allowReceiveGameMessage(class_2561 message, boolean overlay)` |
| `fabric-message-api-v1-6.1.12+4ebb5c083e` | `ClientReceiveMessageEvents` | `CHAT` | `onReceiveChatMessage(class_2561 message, @Nullable class_7471 signedMessage, @Nullable GameProfile sender, class_2556.class_7602 params, Instant receptionTimestamp)` |
| `fabric-message-api-v1-6.1.12+4ebb5c083e` | `ClientReceiveMessageEvents` | `CHAT_CANCELED` | `onReceiveChatMessageCanceled(class_2561 message, @Nullable class_7471 signedMessage, @Nullable GameProfile sender, class_2556.class_7602 params, Instant receptionTimestamp)` |
| `fabric-message-api-v1-6.1.12+4ebb5c083e` | `ClientReceiveMessageEvents` | `GAME` | `onReceiveGameMessage(class_2561 message, boolean overlay)` |
| `fabric-message-api-v1-6.1.12+4ebb5c083e` | `ClientReceiveMessageEvents` | `GAME_CANCELED` | `onReceiveGameMessageCanceled(class_2561 message, boolean overlay)` |
| `fabric-message-api-v1-6.1.12+4ebb5c083e` | `ClientReceiveMessageEvents` | `MODIFY_GAME` | `(未解析)` |
| `fabric-message-api-v1-6.1.12+4ebb5c083e` | `ClientSendMessageEvents` | `ALLOW_CHAT` | `allowSendChatMessage(String message)` |
| `fabric-message-api-v1-6.1.12+4ebb5c083e` | `ClientSendMessageEvents` | `ALLOW_COMMAND` | `allowSendCommandMessage(String command)` |
| `fabric-message-api-v1-6.1.12+4ebb5c083e` | `ClientSendMessageEvents` | `CHAT` | `onSendChatMessage(String message)` |
| `fabric-message-api-v1-6.1.12+4ebb5c083e` | `ClientSendMessageEvents` | `CHAT_CANCELED` | `onSendChatMessageCanceled(String message)` |
| `fabric-message-api-v1-6.1.12+4ebb5c083e` | `ClientSendMessageEvents` | `COMMAND` | `onSendCommandMessage(String command)` |
| `fabric-message-api-v1-6.1.12+4ebb5c083e` | `ClientSendMessageEvents` | `COMMAND_CANCELED` | `onSendCommandMessageCanceled(String command)` |
| `fabric-message-api-v1-6.1.12+4ebb5c083e` | `ClientSendMessageEvents` | `MODIFY_CHAT` | `(未解析)` |
| `fabric-message-api-v1-6.1.12+4ebb5c083e` | `ClientSendMessageEvents` | `MODIFY_COMMAND` | `(未解析)` |
| `fabric-message-api-v1-6.1.12+4ebb5c083e` | `ServerMessageDecoratorEvent` | `EVENT` | `(未解析)` |
| `fabric-message-api-v1-6.1.12+4ebb5c083e` | `ServerMessageEvents` | `ALLOW_CHAT_MESSAGE` | `allowChatMessage(class_7471 message, class_3222 sender, class_2556.class_7602 params)` |
| `fabric-message-api-v1-6.1.12+4ebb5c083e` | `ServerMessageEvents` | `ALLOW_COMMAND_MESSAGE` | `allowCommandMessage(class_7471 message, class_2168 source, class_2556.class_7602 params)` |
| `fabric-message-api-v1-6.1.12+4ebb5c083e` | `ServerMessageEvents` | `ALLOW_GAME_MESSAGE` | `allowGameMessage(MinecraftServer server, class_2561 message, boolean overlay)` |
| `fabric-message-api-v1-6.1.12+4ebb5c083e` | `ServerMessageEvents` | `CHAT_MESSAGE` | `onChatMessage(class_7471 message, class_3222 sender, class_2556.class_7602 params)` |
| `fabric-message-api-v1-6.1.12+4ebb5c083e` | `ServerMessageEvents` | `COMMAND_MESSAGE` | `onCommandMessage(class_7471 message, class_2168 source, class_2556.class_7602 params)` |
| `fabric-message-api-v1-6.1.12+4ebb5c083e` | `ServerMessageEvents` | `GAME_MESSAGE` | `onGameMessage(MinecraftServer server, class_2561 message, boolean overlay)` |
| `fabric-networking-api-v1-5.1.5+ae1e07683e` | `C2SConfigurationChannelEvents` | `REGISTER` | `onChannelRegister(class_8674 handler, PacketSender sender, class_310 client, List<class_2960> channels)` |
| `fabric-networking-api-v1-5.1.5+ae1e07683e` | `C2SConfigurationChannelEvents` | `UNREGISTER` | `onChannelUnregister(class_8674 handler, PacketSender sender, class_310 client, List<class_2960> channels)` |
| `fabric-networking-api-v1-5.1.5+ae1e07683e` | `C2SPlayChannelEvents` | `REGISTER` | `onChannelRegister(class_634 handler, PacketSender sender, class_310 client, List<class_2960> channels)` |
| `fabric-networking-api-v1-5.1.5+ae1e07683e` | `C2SPlayChannelEvents` | `UNREGISTER` | `onChannelUnregister(class_634 handler, PacketSender sender, class_310 client, List<class_2960> channels)` |
| `fabric-networking-api-v1-5.1.5+ae1e07683e` | `ClientConfigurationConnectionEvents` | `COMPLETE` | `onConfigurationComplete(class_8674 handler, class_310 client)` |
| `fabric-networking-api-v1-5.1.5+ae1e07683e` | `ClientConfigurationConnectionEvents` | `DISCONNECT` | `onConfigurationDisconnect(class_8674 handler, class_310 client)` |
| `fabric-networking-api-v1-5.1.5+ae1e07683e` | `ClientConfigurationConnectionEvents` | `INIT` | `onConfigurationInit(class_8674 handler, class_310 client)` |
| `fabric-networking-api-v1-5.1.5+ae1e07683e` | `ClientConfigurationConnectionEvents` | `READY` | `onConfigurationReady(class_8674 handler, class_310 client)` |
| `fabric-networking-api-v1-5.1.5+ae1e07683e` | `ClientConfigurationConnectionEvents` | `START` | `onConfigurationStart(class_8674 handler, class_310 client)` |
| `fabric-networking-api-v1-5.1.5+ae1e07683e` | `ClientLoginConnectionEvents` | `DISCONNECT` | `onLoginDisconnect(class_635 handler, class_310 client)` |
| `fabric-networking-api-v1-5.1.5+ae1e07683e` | `ClientLoginConnectionEvents` | `INIT` | `onLoginStart(class_635 handler, class_310 client)` |
| `fabric-networking-api-v1-5.1.5+ae1e07683e` | `ClientLoginConnectionEvents` | `QUERY_START` | `onLoginQueryStart(class_635 handler, class_310 client)` |
| `fabric-networking-api-v1-5.1.5+ae1e07683e` | `ClientPlayConnectionEvents` | `DISCONNECT` | `onPlayDisconnect(class_634 handler, class_310 client)` |
| `fabric-networking-api-v1-5.1.5+ae1e07683e` | `ClientPlayConnectionEvents` | `INIT` | `onPlayInit(class_634 handler, class_310 client)` |
| `fabric-networking-api-v1-5.1.5+ae1e07683e` | `ClientPlayConnectionEvents` | `JOIN` | `onPlayReady(class_634 handler, PacketSender sender, class_310 client)` |
| `fabric-networking-api-v1-5.1.5+ae1e07683e` | `EntityTrackingEvents` | `START_TRACKING` | `onStartTracking(class_1297 trackedEntity, class_3222 player)` |
| `fabric-networking-api-v1-5.1.5+ae1e07683e` | `EntityTrackingEvents` | `STOP_TRACKING` | `onStopTracking(class_1297 trackedEntity, class_3222 player)` |
| `fabric-networking-api-v1-5.1.5+ae1e07683e` | `S2CConfigurationChannelEvents` | `REGISTER` | `onChannelRegister(class_8610 handler, PacketSender sender, MinecraftServer server, List<class_2960> channels)` |
| `fabric-networking-api-v1-5.1.5+ae1e07683e` | `S2CConfigurationChannelEvents` | `UNREGISTER` | `onChannelUnregister(class_8610 handler, PacketSender sender, MinecraftServer server, List<class_2960> channels)` |
| `fabric-networking-api-v1-5.1.5+ae1e07683e` | `S2CPlayChannelEvents` | `REGISTER` | `onChannelRegister(class_3244 handler, PacketSender sender, MinecraftServer server, List<class_2960> channels)` |
| `fabric-networking-api-v1-5.1.5+ae1e07683e` | `S2CPlayChannelEvents` | `UNREGISTER` | `onChannelUnregister(class_3244 handler, PacketSender sender, MinecraftServer server, List<class_2960> channels)` |
| `fabric-networking-api-v1-5.1.5+ae1e07683e` | `ServerConfigurationConnectionEvents` | `BEFORE_CONFIGURE` | `onSendConfiguration(class_8610 handler, MinecraftServer server)` |
| `fabric-networking-api-v1-5.1.5+ae1e07683e` | `ServerConfigurationConnectionEvents` | `CONFIGURE` | `onSendConfiguration(class_8610 handler, MinecraftServer server)` |
| `fabric-networking-api-v1-5.1.5+ae1e07683e` | `ServerConfigurationConnectionEvents` | `DISCONNECT` | `onConfigureDisconnect(class_8610 handler, MinecraftServer server)` |
| `fabric-networking-api-v1-5.1.5+ae1e07683e` | `ServerLoginConnectionEvents` | `DISCONNECT` | `onLoginDisconnect(class_3248 handler, MinecraftServer server)` |
| `fabric-networking-api-v1-5.1.5+ae1e07683e` | `ServerLoginConnectionEvents` | `INIT` | `onLoginInit(class_3248 handler, MinecraftServer server)` |
| `fabric-networking-api-v1-5.1.5+ae1e07683e` | `ServerLoginConnectionEvents` | `QUERY_START` | `onLoginStart(class_3248 handler, MinecraftServer server, LoginPacketSender sender, ServerLoginNetworking.LoginSynchronizer synchronizer)` |
| `fabric-networking-api-v1-5.1.5+ae1e07683e` | `ServerPlayConnectionEvents` | `DISCONNECT` | `onPlayDisconnect(class_3244 handler, MinecraftServer server)` |
| `fabric-networking-api-v1-5.1.5+ae1e07683e` | `ServerPlayConnectionEvents` | `INIT` | `onPlayInit(class_3244 handler, MinecraftServer server)` |
| `fabric-networking-api-v1-5.1.5+ae1e07683e` | `ServerPlayConnectionEvents` | `JOIN` | `onPlayReady(class_3244 handler, PacketSender sender, MinecraftServer server)` |
| `fabric-particles-v1-4.2.11+4fc5413f3e` | `ParticleRenderEvents` | `ALLOW_BLOCK_DUST_TINT` | `allowBlockDustTint(class_2680 state, class_638 world, class_2338 pos)` |
| `fabric-rendering-v1-16.2.8+f4ffd2e53e` | `LivingEntityFeatureRenderEvents` | `ALLOW_CAPE_RENDER` | `allowCapeRender(class_10055 state)` |
| `fabric-rendering-v1-16.2.8+f4ffd2e53e` | `WorldRenderEvents` | `AFTER_BLOCK_OUTLINE_EXTRACTION` | `afterBlockOutlineExtraction(WorldExtractionContext context, @Nullable class_239 result)` |
| `fabric-rendering-v1-16.2.8+f4ffd2e53e` | `WorldRenderEvents` | `AFTER_ENTITIES` | `afterEntities(WorldRenderContext context)` |
| `fabric-rendering-v1-16.2.8+f4ffd2e53e` | `WorldRenderEvents` | `BEFORE_BLOCK_OUTLINE` | `beforeBlockOutline(WorldRenderContext context, class_12074 outlineRenderState)` |
| `fabric-rendering-v1-16.2.8+f4ffd2e53e` | `WorldRenderEvents` | `BEFORE_DEBUG_RENDER` | `beforeDebugRender(WorldRenderContext context)` |
| `fabric-rendering-v1-16.2.8+f4ffd2e53e` | `WorldRenderEvents` | `BEFORE_ENTITIES` | `beforeEntities(WorldRenderContext context)` |
| `fabric-rendering-v1-16.2.8+f4ffd2e53e` | `WorldRenderEvents` | `BEFORE_TRANSLUCENT` | `beforeTranslucent(WorldRenderContext context)` |
| `fabric-rendering-v1-16.2.8+f4ffd2e53e` | `WorldRenderEvents` | `END_EXTRACTION` | `endExtraction(WorldExtractionContext context)` |
| `fabric-rendering-v1-16.2.8+f4ffd2e53e` | `WorldRenderEvents` | `END_MAIN` | `endMain(WorldRenderContext context)` |
| `fabric-rendering-v1-16.2.8+f4ffd2e53e` | `WorldRenderEvents` | `START_MAIN` | `startMain(WorldTerrainRenderContext context)` |
| `fabric-screen-api-v1-3.1.7+4ebb5c083e` | `ScreenEvents` | `AFTER_INIT` | `afterInit(class_310 client, class_437 screen, int scaledWidth, int scaledHeight)` |
| `fabric-screen-api-v1-3.1.7+4ebb5c083e` | `ScreenEvents` | `BEFORE_INIT` | `beforeInit(class_310 client, class_437 screen, int scaledWidth, int scaledHeight)` |
| `fabric-transfer-api-v1-6.0.24+4fc5413f3e` | `FluidStorage` | `GENERAL_COMBINED_PROVIDER` | `(未解析)` |
