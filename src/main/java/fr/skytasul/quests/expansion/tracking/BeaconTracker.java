package fr.skytasul.quests.expansion.tracking;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import fr.skytasul.quests.api.QuestsPlugin;
import fr.skytasul.quests.api.editors.parsers.EnumParser;
import fr.skytasul.quests.api.gui.LoreBuilder;
import fr.skytasul.quests.api.localization.Lang;
import fr.skytasul.quests.api.objects.QuestObjectClickEvent;
import fr.skytasul.quests.api.stages.types.Locatable;
import fr.skytasul.quests.api.stages.types.Locatable.Located;
import fr.skytasul.quests.api.stages.types.Locatable.PreciseLocatable;
import fr.skytasul.quests.expansion.api.tracking.Tracker;

public class BeaconTracker extends AbstractTaskTracker {

	private static final DyeColor DEFAULT_COLOR = DyeColor.LIME;
	private static final int TARGET_TIME_MODULO = 78;

	private static final BlockData DATA_BEACON = Bukkit.createBlockData(Material.BEACON);
	private static final BlockData DATA_BLOCK = Bukkit.createBlockData(Material.IRON_BLOCK);
	private BlockData dataGlass;

	private int minDistance;
	private DyeColor color;

	private Locatable.PreciseLocatable precise;
	private Map<Player, PlayerBeacon> shown;
	private World world = null;

	private boolean showingState = false;

	public BeaconTracker() {
		this(40, DEFAULT_COLOR);
	}

	public BeaconTracker(int minDistance, DyeColor color) {
		super(45);
		this.minDistance = minDistance;
		setColor(color);
	}

	public void setColor(DyeColor color) {
		this.color = color;
		this.dataGlass = Bukkit.createBlockData(Material.valueOf(color.name() + "_STAINED_GLASS"));
	}

	@Override
	public Tracker clone() {
		return new BeaconTracker(minDistance, color);
	}

	@Override
	protected long getDelay() {
		if (world == null) return 10;
		long gameTime = world.getGameTime();
		long modulo = gameTime % 80;
		if (modulo > TARGET_TIME_MODULO)
			return 80 + TARGET_TIME_MODULO - modulo;
		else return TARGET_TIME_MODULO - modulo;
	}

	@Override
	public void start(Locatable locatable) {
		precise = (PreciseLocatable) locatable;
		Located tmpLocated = precise.getLocated();
		if (tmpLocated != null) world = tmpLocated.getLocation().getWorld();
		showingState = true;
		super.start(locatable);
		if (precise.canBeFetchedAsynchronously()) {
			shown = new ConcurrentHashMap<>();
		}else {
			shown = new HashMap<>();
		}
	}

	@Override
	public void stop() {
		super.stop();
		if (shown != null && !shown.isEmpty()) shown.values().forEach(PlayerBeacon::remove);
	}

	@Override
	public void run() {
		Located located = precise.getLocated();
		if (located == null) {
			shown.values().forEach(PlayerBeacon::remove);
		}else {
			Location location = located.getLocation();
			if (location.getWorld() != world) {
				stop();
				world = location.getWorld();
				showingState = true;
				super.start(precise);
				return;
			}
			shown.values().forEach(beacon -> beacon.show(location));
		}
		showingState = !showingState;
	}

	@Override
	public void show(Player player) {
		shown.put(player, new PlayerBeacon(player));
	}

	@Override
	public void hide(Player player) {
		PlayerBeacon beacon = shown.remove(player);
		if (beacon != null) beacon.remove();
	}

	@Override
	protected void addLore(LoreBuilder loreBuilder) {
		super.addLore(loreBuilder);
		loreBuilder.addDescriptionAsValue(color.name().toLowerCase().replace('_', ' '));
	}

	@Override
	public void itemClick(QuestObjectClickEvent event) {
		if (event.isInCreation()) return;
		
		QuestsPlugin.getPlugin().getEditorManager().getFactory().createTextEditorBuilderParser(event.getPlayer(),
				new EnumParser<>(DyeColor.class), event::reopenGUI, newColor -> {
					setColor(newColor);
					event.reopenGUI();
				})
				.addReset(() -> {
					setColor(DEFAULT_COLOR);
					event.reopenGUI();
				}, "reset")
				.setIndication(Lang.COLOR_NAMED_EDITOR.toString())
				.setInitialValue(color)
				.build().start();
	}

	@Override
	public void save(ConfigurationSection section) {
		if (color != DEFAULT_COLOR) section.set("color", color.name());
	}

	@Override
	public void load(ConfigurationSection section) {
		if (section.contains("color")) setColor(DyeColor.valueOf(section.getString("color")));
	}

	class PlayerBeacon {

		private final Player player;
		private Location lastLocation;

		public PlayerBeacon(Player player) {
			this.player = player;
		}

		public void show(Location location) {
			Location playerLocation = player.getEyeLocation();
			if (!playerLocation.getWorld().equals(location.getWorld())) {
				remove();
				lastLocation = null;
				return;
			}

			int viewDistance = Math.min(8, player.getClientViewDistance());

			double distanceSquared = location.distanceSquared(playerLocation);
			if (distanceSquared < minDistance * minDistance || isVisible(playerLocation, location.clone().add(0, 2, 0))) {
				remove();
				lastLocation = null;
				return;
			}else if (distanceSquared > viewDistance * viewDistance * 256) {
				// if player too far away, the beacon cannot be spawned directly on location
				Vector direction = location.toVector().subtract(playerLocation.toVector());
				direction.normalize().multiply(viewDistance * 16);
				location = playerLocation.add(direction);
				if (lastLocation != null && (lastLocation.getBlockX() >> 4 == location.getBlockX() >> 4) && (lastLocation.getBlockZ() >> 4 == location.getBlockZ() >> 4))
					return; // if same chunk: no change
			}else if (location.equals(lastLocation)) return;

			if (!location.equals(lastLocation) && showingState) {
				remove();
				lastLocation = location;
				place(location.clone(), false);
			}
		}

		private boolean isVisible(Location location1, Location location2) {
			Vector direction = location2.toVector().subtract(location1.toVector());
			RayTraceResult result = location1.getWorld().rayTraceBlocks(location1, direction, direction.length() - 2);
			return result == null;
		}

		private void remove() {
			if (lastLocation != null && lastLocation.isWorldLoaded() && lastLocation.getWorld().equals(player.getLocation().getWorld()) && lastLocation.getChunk().isLoaded()) {
				place(lastLocation, true);
				lastLocation = null;
			}
		}

		private void place(Location location, boolean destroy) {
			location.setY(location.getWorld().getHighestBlockYAt(location) + 3);
			player.sendBlockChange(location, destroy ? location.getBlock().getBlockData() : dataGlass);
			location.setY(location.getY() - 1);
			player.sendBlockChange(location, destroy ? location.getBlock().getBlockData() : DATA_BEACON);
			location.setY(location.getY() - 1);
			int minX = location.getBlockX() - 1;
			int maxX = location.getBlockX() + 1;
			int minZ = location.getBlockZ() - 1;
			int maxZ = location.getBlockZ() + 1;
			for (int x = minX; x <= maxX; x++) {
				location.setX(x);
				for (int z = minZ; z <= maxZ; z++) {
					location.setZ(z);
					player.sendBlockChange(location, destroy ? location.getBlock().getBlockData() : DATA_BLOCK);
				}
			}
		}

	}

}
