package com.boosticon;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PartyChanged;
import net.runelite.client.input.KeyManager;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.WSClient;
import net.runelite.client.party.events.UserPart;
import net.runelite.client.party.messages.UserSync;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.party.messages.StatusUpdate;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.HotkeyListener;
import net.runelite.client.util.Text;

@PluginDescriptor(
	name = "Boosted Icon",
	description = "Icons by your health bar for boosted or drained combat stats",
	tags = {"boost", "drain", "potion", "stats", "combat", "party"}
)
public class BoostIconPlugin extends Plugin
{
	private static final CombatStat[] STATS = CombatStat.ALL;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private BoostIconConfig config;

	@Inject
	private BoostIconOverlay overlay;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private PartyService partyService;

	@Inject
	private WSClient wsClient;

	@Inject
	private KeyManager keyManager;

	/**
	 * Whether the key has been used to put the icons away. Set from the keyboard and read while the
	 * overlay is drawn, which are not the same thread.
	 */
	private volatile boolean hidden;

	private final HotkeyListener toggle = new HotkeyListener(() -> config.toggle())
	{
		@Override
		public void hotkeyPressed()
		{
			hidden = !hidden;
		}
	};

	private final List<StatColumn> columns = new ArrayList<>();

	/**
	 * The settings as they stand, read in one go rather than one at a time while drawing. Replaced rather
	 * than changed when one of them changes, so that whichever thread is reading a set has the whole of
	 * it as it was at one moment.
	 */
	private volatile Settings settings;

	/**
	 * The levels each party member has shared, held until they share different ones or leave.
	 */
	private final Map<Long, PartyLevels> partyLevels = new HashMap<>();

	/**
	 * What was last sent to the party, so a tick that changed nothing sends nothing.
	 */
	private String sentName;
	private int[] sentBoosted;
	private int[] sentReal;

	@Override
	protected void startUp()
	{
		settings = new Settings(config);
		overlayManager.add(overlay);
		keyManager.registerKeyListener(toggle);
		wsClient.registerMessage(BoostIconStats.class);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		keyManager.unregisterKeyListener(toggle);
		hidden = false;
		wsClient.unregisterMessage(BoostIconStats.class);
		columns.clear();
		partyLevels.clear();
		forgetSent();
	}

	@Provides
	BoostIconConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BoostIconConfig.class);
	}

	/**
	 * Whether the icons have been put away with the toggle key, which stops them being drawn and
	 * nothing else: levels are still kept up to date and still shared with the party.
	 */
	boolean isHidden()
	{
		return hidden;
	}

	/**
	 * The icons to draw this tick. The overlay reads this on the same thread that fills it in, and is
	 * handed the list itself rather than a wrapper around it, which would be an object made for every
	 * frame to hold something that is only ever read.
	 */
	List<StatColumn> getColumns()
	{
		return columns;
	}

	/**
	 * The settings as they stand, for the overlay to draw a whole frame by.
	 */
	Settings getSettings()
	{
		return settings;
	}

	/**
	 * Levels arrive with the rest of the tick, and a boost that wears off is a tick's news as well, so
	 * the whole set is read fresh each tick rather than followed through stat change events. Nothing to
	 * get out of step with, and a party member's levels are treated the same way.
	 */
	@Subscribe
	public void onGameTick(GameTick event)
	{
		Settings settings = this.settings;

		columns.clear();

		int[] boosted = new int[STATS.length];
		int[] real = new int[STATS.length];

		for (int i = 0; i < STATS.length; i++)
		{
			boosted[i] = now(STATS[i]);
			real[i] = full(STATS[i]);
		}

		if (settings.showSelf())
		{
			addColumn(settings, client.getLocalPlayer(), boosted, real);
		}

		share(settings, boosted, real);

		if (!settings.partyStats() || !partyService.isInParty())
		{
			partyLevels.clear();
			return;
		}

		if (partyLevels.isEmpty())
		{
			// Nobody has shared anything, so there is nobody to go looking for among the players around
			return;
		}

		Map<String, Player> nearby = playersByName();
		PartyMember local = partyService.getLocalMember();

		for (PartyMember member : partyService.getMembers())
		{
			if (local != null && member.getMemberId() == local.getMemberId())
			{
				continue;
			}

			PartyLevels levels = partyLevels.get(member.getMemberId());

			if (levels == null)
			{
				continue;
			}

			String name = nameOf(member, levels);

			if (name == null)
			{
				continue;
			}

			addColumn(settings, nearby.get(Text.standardize(name)), levels.getBoosted(), levels.getReal());
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() != GameState.LOGGED_IN)
		{
			columns.clear();
			forgetSent();
		}
	}

	/**
	 * Levels belong to the party they were shared with, and a different party is different people.
	 */
	@Subscribe
	public void onPartyChanged(PartyChanged event)
	{
		clientThread.invoke(() ->
		{
			partyLevels.clear();
			forgetSent();
		});

		requestSync();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!BoostIconConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}

		// Read again here rather than while drawing, so that a change shows on the next frame either way
		settings = new Settings(config);

		if ("partyStats".equals(event.getKey()))
		{
			requestSync();
		}
	}

	@Subscribe
	public void onBoostIconStats(BoostIconStats message)
	{
		int[] boosted = message.getBoosted();
		int[] real = message.getReal();

		if (boosted == null || real == null
			|| boosted.length != STATS.length || real.length != STATS.length)
		{
			return;
		}

		long memberId = message.getMemberId();
		String name = message.getName();

		if (!settings.partyStats())
		{
			return;
		}

		clientThread.invoke(() ->
		{
			if (isOwn(memberId))
			{
				return;
			}

			PartyLevels levels = levelsFor(memberId);

			if (name != null && !name.isEmpty())
			{
				levels.setName(name);
			}

			for (int i = 0; i < STATS.length; i++)
			{
				set(levels, STATS[i], boosted[i], real[i]);
			}
		});
	}

	@Subscribe
	public void onUserPart(UserPart event)
	{
		clientThread.invoke(() -> partyLevels.remove(event.getMemberId()));
	}

	/**
	 * Somebody has just joined and has nothing of anyone's yet, so the next tick sends again.
	 */
	@Subscribe
	public void onUserSync(UserSync event)
	{
		clientThread.invoke(this::forgetSent);
	}

	/**
	 * What the client's own party plugin already shares, which covers hitpoints, prayer and special
	 * attack for anyone running it, with or without this plugin. A status carries only the parts of
	 * itself that changed, so each half of each stat is taken on its own and the rest of what is known
	 * about that member is left as it was.
	 */
	@Subscribe
	public void onStatusUpdate(StatusUpdate event)
	{
		long memberId = event.getMemberId();

		if (!settings.partyStats())
		{
			return;
		}

		Integer spec = event.getSpecEnergy();

		clientThread.invoke(() ->
		{
			if (isOwn(memberId))
			{
				return;
			}

			PartyLevels levels = levelsFor(memberId);

			set(levels, CombatStat.HITPOINTS, event.getHealthCurrent(), event.getHealthMax());
			set(levels, CombatStat.PRAYER, event.getPrayerCurrent(), event.getPrayerMax());

			// A bar is full at the same amount for everyone, so it is known as soon as the amount is
			set(levels, CombatStat.SPECIAL, spec, spec == null ? null : CombatStat.FULL_PERCENT);
		});
	}

	/**
	 * One stat out of a status, where either half can be missing because it had not changed. Levels
	 * come from whoever is sharing them and are taken as nothing more than numbers, so anything a stat
	 * could not be at is dropped rather than drawn.
	 */
	private static void set(PartyLevels levels, CombatStat stat, Integer boostedLevel, Integer realLevel)
	{
		if (boostedLevel != null && stat.isPossibleNow(boostedLevel))
		{
			levels.setBoosted(stat, boostedLevel);
		}

		if (realLevel != null && stat.isPossibleReal(realLevel))
		{
			levels.setReal(stat, realLevel);
		}
	}

	/**
	 * The name of the player a party member is playing as. The party itself knows them by an id and
	 * nothing more: a name is put to one by the party plugin that comes with the client, and only while
	 * that plugin is running, so what a member shared of their own name comes first. That is what lets
	 * the icons appear for a team running this plugin and not that one.
	 */
	private static String nameOf(PartyMember member, PartyLevels levels)
	{
		String shared = levels.getName();

		if (shared != null)
		{
			return shared;
		}

		// Set alongside the name by the party plugin, so a member it has not named is a member still
		// going by the stand-in name every member starts out with
		return member.isLoggedIn() ? member.getDisplayName() : null;
	}

	/**
	 * Whether a member id is our own. Our levels are read from the client rather than taken off the
	 * party, so our own share of them coming back around is dropped.
	 */
	private boolean isOwn(long memberId)
	{
		PartyMember local = partyService.getLocalMember();

		return local != null && local.getMemberId() == memberId;
	}

	private PartyLevels levelsFor(long memberId)
	{
		return partyLevels.computeIfAbsent(memberId, id -> new PartyLevels());
	}

	/**
	 * Asks the party for the levels it has now, rather than waiting on everyone's next change.
	 */
	void requestSync()
	{
		if (settings.partyStats() && partyService.isInParty())
		{
			partyService.send(new UserSync());
		}
	}

	/**
	 * What the stat is at now. Special attack is not a skill and is kept in tenths of a per cent, which
	 * is finer than anything read off it here, so it is taken down to whole per cent.
	 */
	private int now(CombatStat stat)
	{
		return stat.isPercent()
			? client.getVarpValue(VarPlayerID.SA_ENERGY) / 10
			: client.getBoostedSkillLevel(stat.getSkill());
	}

	/**
	 * What the stat sits at with nothing acting on it, which for special attack is a full bar.
	 */
	private int full(CombatStat stat)
	{
		return stat.isPercent() ? CombatStat.FULL_PERCENT : client.getRealSkillLevel(stat.getSkill());
	}

	private void addColumn(Settings settings, Player player, int[] boosted, int[] real)
	{
		if (player == null || player.getLocalLocation() == null)
		{
			return;
		}

		List<StatChange> changes = null;

		for (int i = 0; i < STATS.length; i++)
		{
			CombatStat stat = STATS[i];

			if (boosted[i] == PartyLevels.UNKNOWN || real[i] == PartyLevels.UNKNOWN)
			{
				continue;
			}

			if (!settings.isEnabled(i))
			{
				continue;
			}

			int change = boosted[i] - real[i];

			// Hitpoints, prayer and special attack are an amount rather than a boost or a drain, so
			// having turned one on is asking to see it, full bar and all
			if (!stat.isPoints()
				&& ((change == 0 && !settings.showUnchanged())
				|| (change > 0 && !settings.showBuffs())
				|| (change < 0 && !settings.showDebuffs())))
			{
				continue;
			}

			if (changes == null)
			{
				// Left until there is something to put in it, since most ticks of most players have
				// nothing off its level at all
				changes = new ArrayList<>(STATS.length);
			}

			changes.add(new StatChange(stat, boosted[i], change));
		}

		if (changes != null)
		{
			columns.add(new StatColumn(player, changes));
		}
	}

	private void share(Settings settings, int[] boosted, int[] real)
	{
		if (!settings.partyStats() || !partyService.isInParty())
		{
			forgetSent();
			return;
		}

		Player local = client.getLocalPlayer();
		String name = local == null ? null : local.getName();

		if (Objects.equals(name, sentName)
			&& Arrays.equals(boosted, sentBoosted) && Arrays.equals(real, sentReal))
		{
			return;
		}

		sentName = name;
		sentBoosted = boosted.clone();
		sentReal = real.clone();
		partyService.send(new BoostIconStats(sentName, sentBoosted, sentReal));
	}

	private void forgetSent()
	{
		sentName = null;
		sentBoosted = null;
		sentReal = null;
	}

	/**
	 * The players around you, by the name the party knows them as. Built once a tick rather than looked
	 * through again for each party member, since a busy world is a great many names to go over.
	 */
	private Map<String, Player> playersByName()
	{
		Map<String, Player> players = new HashMap<>();

		for (Player player : client.getTopLevelWorldView().players())
		{
			String name = player.getName();

			if (name != null)
			{
				// The first of them, as a name is only shared by a player and whatever is mimicking them
				players.putIfAbsent(Text.standardize(name), player);
			}
		}

		return players;
	}
}
