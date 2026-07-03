package skillcape;

import com.google.inject.Provides;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.DecorativeObjectSpawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GroundObjectSpawned;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.WallObjectSpawned;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.RenderCallback;
import net.runelite.client.callback.RenderCallbackManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
	name = "Maggot King Helper"
)
public class MaggotKingHelperPlugin extends Plugin implements RenderCallback
{
	private static final String DUMP_COMMAND = "mdb";
	private static final int COLLECTION_SECONDS = 3;
	private static final File DUMP_FILE = new File(RuneLite.RUNELITE_DIR, "maggot-king-helper/object-dump.csv");

	private static final int MAGGOT_KING_ARENA_REGION = 11645;

	private static final Set<Integer> DARKWOOD_TREE_IDS = new HashSet<>(Arrays.asList(
		40788, 40789, 40790, 40791, 40793, 40796, 40802, 40803, 40804, 40805, 40806, 40808, 40809,
		40810, 40811, 40812, 40813, 40814, 40816, 40817, 40818, 40819, 40820, 40821, 40822, 40825
	));

	private static final Set<Integer> EXIT_DOOR_IDS = new HashSet<>(Arrays.asList(61049));

	private static final Set<Integer> CARRION_IDS = new HashSet<>(Arrays.asList(33425));

	private static final Set<Integer> ALL_HIDEABLE_IDS = new HashSet<>();

	static
	{
		ALL_HIDEABLE_IDS.addAll(DARKWOOD_TREE_IDS);
		ALL_HIDEABLE_IDS.addAll(EXIT_DOOR_IDS);
		ALL_HIDEABLE_IDS.addAll(CARRION_IDS);
	}

	// see net.runelite.api.hooks.DrawCallbacks#invalidateZone: zone index = (scene tile >> 3) + this offset
	private static final int ZONE_OFFSET = (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2 >> 3;

	@Inject
	private Client client;

	@Inject
	private MaggotKingHelperConfig config;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private RenderCallbackManager renderCallbackManager;

	private final Set<Integer> seenObjectIds = new HashSet<>();

	private volatile boolean collecting;
	private ScheduledFuture<?> stopTask;

	@Override
	protected void startUp() throws Exception
	{
		log.debug("Maggot King Helper started!");
		seenObjectIds.clear();
		collecting = false;

		renderCallbackManager.register(this);
		clientThread.invokeLater(() -> invalidateZonesForIds(ALL_HIDEABLE_IDS));
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.debug("Maggot King Helper stopped!");
		collecting = false;
		if (stopTask != null)
		{
			stopTask.cancel(false);
			stopTask = null;
		}

		renderCallbackManager.unregister(this);
		clientThread.invokeLater(() -> invalidateZonesForIds(ALL_HIDEABLE_IDS));
	}

	// --- RenderCallback: called off the client thread for every object considered for drawing ---

	@Override
	public boolean drawObject(Scene scene, TileObject object)
	{
		if (!isMaggotKingArenaScene(scene))
		{
			return true;
		}

		int id = object.getId();
		boolean shouldHide = config.hideDarkwoodTrees() && DARKWOOD_TREE_IDS.contains(id)
			|| config.hideExitDoor() && EXIT_DOOR_IDS.contains(id)
			|| config.hideCarrion() && CARRION_IDS.contains(id);

		return !shouldHide;
	}

	private static boolean isMaggotKingArenaScene(Scene scene)
	{
		if (scene == null)
		{
			return false;
		}

		for (int region : scene.getMapRegions())
		{
			if (region == MAGGOT_KING_ARENA_REGION)
			{
				return true;
			}
		}

		return false;
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (!config.hideExitDoorMenuOption())
		{
			return;
		}

		if (!EXIT_DOOR_IDS.contains(event.getIdentifier()))
		{
			return;
		}

		if (!isMaggotKingArenaScene(client.getTopLevelWorldView().getScene()))
		{
			return;
		}

		// Demote (rather than remove) so the option no longer fires on a plain left click,
		// but is still selectable from the right-click menu.
		event.getMenuEntry().setDeprioritized(true);
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted commandExecuted)
	{
		if (!DUMP_COMMAND.equalsIgnoreCase(commandExecuted.getCommand()))
		{
			return;
		}

		if (stopTask != null)
		{
			stopTask.cancel(false);
		}

		seenObjectIds.clear();
		collecting = true;
		client.addChatMessage(ChatMessageType.CONSOLE, "", "Maggot King Helper: collecting object IDs for " + COLLECTION_SECONDS + " seconds...", null);

		Scene scene = client.getTopLevelWorldView().getScene();
		client.addChatMessage(ChatMessageType.CONSOLE, "", "Maggot King Helper: regions=" + Arrays.toString(scene.getMapRegions()) + " instance=" + scene.isInstance(), null);

		scanScene();

		stopTask = executor.schedule(this::stopCollecting, COLLECTION_SECONDS, TimeUnit.SECONDS);
	}

	private void scanScene()
	{
		Scene scene = client.getTopLevelWorldView().getScene();
		Tile[][][] tiles = scene.getTiles();
		for (Tile[][] plane : tiles)
		{
			for (Tile[] column : plane)
			{
				for (Tile tile : column)
				{
					if (tile == null)
					{
						continue;
					}

					for (GameObject gameObject : tile.getGameObjects())
					{
						if (gameObject != null)
						{
							recordObject(gameObject, "GameObject");
						}
					}

					WallObject wallObject = tile.getWallObject();
					if (wallObject != null)
					{
						recordObject(wallObject, "WallObject");
					}

					DecorativeObject decorativeObject = tile.getDecorativeObject();
					if (decorativeObject != null)
					{
						recordObject(decorativeObject, "DecorativeObject");
					}

					GroundObject groundObject = tile.getGroundObject();
					if (groundObject != null)
					{
						recordObject(groundObject, "GroundObject");
					}
				}
			}
		}
	}

	private void stopCollecting()
	{
		collecting = false;
		clientThread.invoke(() ->
			client.addChatMessage(ChatMessageType.CONSOLE, "", "Maggot King Helper: stopped collecting object IDs.", null));
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!MaggotKingHelperConfig.GROUP.equals(event.getGroup()) || !isHideConfigKey(event.getKey()))
		{
			return;
		}

		clientThread.invokeLater(() -> invalidateZonesForIds(ALL_HIDEABLE_IDS));
	}

	private static boolean isHideConfigKey(String key)
	{
		return "hideDarkwoodTrees".equals(key) || "hideExitDoor".equals(key) || "hideCarrion".equals(key);
	}

	/**
	 * Forces the renderer to redraw the zones containing any of the given object IDs, so a config
	 * toggle takes effect immediately instead of waiting for the next natural scene reload.
	 * Only zones where a matching object is actually found are invalidated, since invalidating an
	 * unloaded/uninitialized zone can crash the GPU plugin.
	 */
	private void invalidateZonesForIds(Set<Integer> objectIds)
	{
		if (objectIds.isEmpty())
		{
			return;
		}

		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null)
		{
			return;
		}

		Scene scene = worldView.getScene();
		if (scene == null)
		{
			return;
		}

		DrawCallbacks drawCallbacks = client.getDrawCallbacks();
		if (drawCallbacks == null)
		{
			return;
		}

		Set<Long> done = new HashSet<>();
		for (Tile[][] plane : scene.getTiles())
		{
			for (Tile[] column : plane)
			{
				for (Tile tile : column)
				{
					if (tile == null)
					{
						continue;
					}

					TileObject found = findAnyObjectOnTile(tile, objectIds);
					if (found != null)
					{
						invalidateZoneForObject(scene, drawCallbacks, found, done);
					}
				}
			}
		}
	}

	private static TileObject findAnyObjectOnTile(Tile tile, Set<Integer> objectIds)
	{
		WallObject wallObject = tile.getWallObject();
		if (wallObject != null && objectIds.contains(wallObject.getId()))
		{
			return wallObject;
		}

		DecorativeObject decorativeObject = tile.getDecorativeObject();
		if (decorativeObject != null && objectIds.contains(decorativeObject.getId()))
		{
			return decorativeObject;
		}

		GroundObject groundObject = tile.getGroundObject();
		if (groundObject != null && objectIds.contains(groundObject.getId()))
		{
			return groundObject;
		}

		for (GameObject gameObject : tile.getGameObjects())
		{
			if (gameObject != null && objectIds.contains(gameObject.getId()))
			{
				return gameObject;
			}
		}

		return null;
	}

	private static void invalidateZoneForObject(Scene scene, DrawCallbacks drawCallbacks, TileObject object, Set<Long> done)
	{
		long hash = object.getHash();
		int sceneX = (int) (hash & 127);
		int sceneZ = (int) ((hash >> 7) & 127);
		int zoneX = (sceneX >> 3) + ZONE_OFFSET;
		int zoneZ = (sceneZ >> 3) + ZONE_OFFSET;
		long key = ((long) zoneX << 32) | zoneZ;
		if (done.add(key))
		{
			drawCallbacks.invalidateZone(scene, zoneX, zoneZ);
		}
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		recordObject(event.getGameObject(), "GameObject");
	}

	@Subscribe
	public void onWallObjectSpawned(WallObjectSpawned event)
	{
		recordObject(event.getWallObject(), "WallObject");
	}

	@Subscribe
	public void onDecorativeObjectSpawned(DecorativeObjectSpawned event)
	{
		recordObject(event.getDecorativeObject(), "DecorativeObject");
	}

	@Subscribe
	public void onGroundObjectSpawned(GroundObjectSpawned event)
	{
		recordObject(event.getGroundObject(), "GroundObject");
	}

	private void recordObject(TileObject object, String type)
	{
		if (!collecting)
		{
			return;
		}

		int id = object.getId();
		if (!seenObjectIds.add(id))
		{
			return;
		}

		ObjectComposition composition = client.getObjectDefinition(id);
		String name = composition == null ? "?" : composition.getName();
		WorldPoint location = object.getWorldLocation();

		log.debug("Maggot King Helper discovered {} id={} name={} at {}", type, id, name, location);

		String line = id + "," + csvEscape(name) + "," + type + "," + location.getX() + "," + location.getY() + "," + location.getPlane();
		writeLine(line);
	}

	private static String csvEscape(String value)
	{
		return "\"" + value.replace("\"", "\"\"") + "\"";
	}

	private void writeLine(String line)
	{
		executor.execute(() ->
		{
			boolean isNewFile = !DUMP_FILE.exists();
			DUMP_FILE.getParentFile().mkdirs();
			try (Writer writer = new FileWriter(DUMP_FILE, StandardCharsets.UTF_8, true))
			{
				if (isNewFile)
				{
					writer.write("id,name,type,x,y,plane\n");
				}
				writer.write(line);
				writer.write("\n");
			}
			catch (IOException e)
			{
				log.warn("Failed to write object dump to {}", DUMP_FILE, e);
			}
		});
	}

	@Provides
	MaggotKingHelperConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(MaggotKingHelperConfig.class);
	}
}
