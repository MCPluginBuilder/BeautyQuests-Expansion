package fr.skytasul.quests.expansion.tracking;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.Vector2;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionType;

import fr.skytasul.quests.api.QuestsPlugin;
import fr.skytasul.quests.api.editors.parsers.ColorParser;
import fr.skytasul.quests.api.gui.LoreBuilder;
import fr.skytasul.quests.api.objects.QuestObjectClickEvent;
import fr.skytasul.quests.expansion.api.tracking.Tracker;
import fr.skytasul.quests.expansion.utils.ShapesAnalysis;
import fr.skytasul.quests.gui.particles.ParticleListGUI;
import fr.skytasul.quests.integrations.worldguard.StageArea;
import fr.skytasul.quests.utils.ParticleEffect;

public class RegionOutlineTracker extends AbstractTaskShownTracker {

	private static final int POINTS_CACHE_TIME = 60 * 1000;
	private static final double DEFAULT_VIEW_DISTANCE = 35;
	private static final ParticleEffect DEFAULT_EFFECT = new ParticleEffect(Particle.FLAME, null, null);

	private double viewDistance = DEFAULT_VIEW_DISTANCE;
	private double resolution = 0.25;

	private ParticleEffect particles = DEFAULT_EFFECT;

	private List<Location> cachedPoints = null;
	private long pointsTime = 0;

	public RegionOutlineTracker() {
		this(DEFAULT_EFFECT);
	}

	public RegionOutlineTracker(ParticleEffect particles) {
		super(20);
		this.particles = particles;
	}

	@Override
	public void run() {
		StageArea regionStage = super.<StageArea>getLocatable();
		ProtectedRegion region = regionStage.getRegion();
		Location regionCenter = regionStage.getLocated().getLocation();
		if (regionCenter == null) return;

		List<Player> visible = shown
			.stream()
				.filter(player -> player.getWorld() == regionStage.getWorld() && player
						.getLocation().distanceSquared(regionCenter) <= viewDistance * viewDistance)
			.collect(Collectors.toList());
		if (!visible.isEmpty()) computePoints(regionStage.getWorld(), region).forEach(point -> particles.sendParticle(point, visible, 0, 0, 0, 1));
	}

	private List<Location> computePoints(World world, ProtectedRegion region) {
		if (System.currentTimeMillis() - pointsTime > POINTS_CACHE_TIME) {
			pointsTime = System.currentTimeMillis();

			if (region.getType() == RegionType.CUBOID) {
				cachedPoints = ShapesAnalysis.getEdgePoints(Arrays.asList(BoundingBox.of(
						BukkitAdapter.adapt(world, region.getMinimumPoint()),
						BukkitAdapter.adapt(world, region.getMaximumPoint())
						)), resolution)
						.stream()
						.map(vector -> vector.toLocation(world))
						.collect(Collectors.toList());
			}else if (region.getType() == RegionType.POLYGON) {
				cachedPoints = new ArrayList<>(region.getPoints().size() * 3 * 4 * 30);

				Vector2 prev = null, first = null;
				for (BlockVector2 blockVec : region.getPoints()) {
					Vector2 vec = blockVec.toVector2().add(0.5, 0.5);
					if (first == null) first = vec;

					if (prev != null) addPoints(world, prev, vec, region, cachedPoints);

					prev = vec;
				}
				addPoints(world, prev, first, region, cachedPoints);
			}
		}
		return cachedPoints;
	}

	private void addPoints(World world, Vector2 from, Vector2 to, ProtectedRegion region, List<Location> points) {
		int yFrom = region.getMinimumPoint().getY();
		int yTo = region.getMaximumPoint().getY() + 1; // +1 to include the upper bound
		Vector2 direction = to.subtract(from);
		double length = direction.length();
		direction = direction.multiply(resolution / length);
		addPoints(world, from, direction, length, yFrom, points);
		addPoints(world, from, direction, length, yTo, points);
		for (double y = yFrom; y <= yTo; y += resolution) {
			points.add(new Location(world, from.getX(), y, from.getZ()));
		}
	}

	private void addPoints(World world, Vector2 from, Vector2 direction, double length, int y, List<Location> points) {
		double x = from.getX();
		double z = from.getZ();
		for (double i = 0; i <= length; i += resolution) {
			points.add(new Location(world, x, y, z));
			x += direction.getX();
			z += direction.getZ();
		}
	}

	@Override
	public Tracker clone() {
		return new RegionOutlineTracker(particles);
	}

	@Override
	public void itemClick(QuestObjectClickEvent event) {
		if (event.isInCreation()) return;
		new ParticleListGUI(newParticle -> {
			if (newParticle != null) {
				if (ParticleEffect.canHaveColor(newParticle)) {
					QuestsPlugin.getPlugin().getEditorManager().getFactory()
							.createTextEditorBuilderParser(event.getPlayer(), ColorParser.PARSER, event::reopenGUI,
									newColor -> {
										particles = new ParticleEffect(newParticle, null, newColor);
										event.reopenGUI();
									})
							.build().start();
					return;
				}
				particles = new ParticleEffect(newParticle, null, null);
			}
			event.reopenGUI();
		}).open(event.getPlayer());
	}

	@Override
	protected void addLore(LoreBuilder loreBuilder) {
		super.addLore(loreBuilder);
		loreBuilder.addDescriptionAsValue(particles);
	}

	@Override
	public void save(ConfigurationSection section) {
		if (particles != DEFAULT_EFFECT) {
			section.set("particleEffect", particles.getParticle().name());
			if (particles.getColor() != null) section.set("particleColor", particles.getColor().serialize());
		}

		if (viewDistance != DEFAULT_VIEW_DISTANCE)
			section.set("viewDistance", viewDistance);
	}

	@Override
	public void load(ConfigurationSection section) {
		if (section.contains("particleEffect")) {
			Particle particle = Particle.valueOf(section.getString("particleEffect").toUpperCase());
			Color color = section.contains("particleColor") ? Color.deserialize(section.getConfigurationSection("particleColor").getValues(false)) : null;
			particles = new ParticleEffect(particle, null, color);
		}
		if (section.contains("viewDistance"))
			viewDistance = section.getDouble("viewDistance");
	}

}
