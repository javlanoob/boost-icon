package com.boosticon;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PartyChanged;
import net.runelite.client.input.KeyManager;
import net.runelite.client.party.PartyDataService;
import net.runelite.client.party.PartyDataType;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.events.UserPart;
import net.runelite.client.party.messages.PartyData;
import net.runelite.client.party.messages.UserSync;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
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

	/**
	 * The stats asked of the party, which is every one of them that is a skill. Special attack is not a
	 * skill and is asked for alongside them, with the name of the player the stats belong to.
	 */
	private static final Skill[] PARTY_SKILLS = Arrays.stream(STATS)
		.map(CombatStat::getSkill)
		.filter(Objects::nonNull)
		.toArray(Skill[]::new);

	private static final PartyDataType[] PARTY_TYPES = {PartyDataType.CHARACTER_NAME, PartyDataType.SPEC};

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
	private PartyDataService partyDataService;

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
	 * The members who have shared a special attack bar. An empty bar and one never heard of both read as
	 * nought off the party, which is the one stat where nought is a real amount to be at.
	 */
	private final Set<Long> specShared = new HashSet<>();

	@Override
	protected void startUp()
	{
		settings = new Settings(config);
		overlayManager.add(overlay);
		keyManager.registerKeyListener(toggle);

		if (settings.partyStats())
		{
			subscribe();
			requestSync();
		}
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		keyManager.unregisterKeyListener(toggle);
		hidden = false;
		unsubscribe();

		// A plugin is stopped on the Swing thread, while everything it has read of the game is written
		// and read a tick at a time on the client's own. Letting go of it over there keeps all of it to
		// the one thread, rather than emptying a list the tick in flight is still walking through
		clientThread.invoke(() ->
		{
			columns.clear();
			specShared.clear();
		});
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

		own(boosted, real);

		if (settings.showSelf())
		{
			addColumn(settings, client.getLocalPlayer(), boosted, real);
		}

		if (!settings.partyStats() || !partyService.isInParty())
		{
			specShared.clear();
			return;
		}

		List<PartyMember> members = partyService.getMembers();

		if (members.size() < 2)
		{
			// Nobody but us, so there is nobody to go looking for among the players around
			return;
		}

		Map<String, Player> nearby = playersByName();
		PartyMember local = partyService.getLocalMember();

		for (PartyMember member : members)
		{
			// A member the party has not named is one still going by the stand-in name they start out
			// with, which is nobody
			if (!member.isLoggedIn() || (local != null && member.getMemberId() == local.getMemberId()))
			{
				continue;
			}

			Player player = nearby.get(Text.standardize(member.getDisplayName()));

			if (player == null)
			{
				continue;
			}

			// Read into the same pair, since our own column was made from them already and a member's
			// is made from them here and then
			shared(member, boosted, real);
			addColumn(settings, player, boosted, real);
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() != GameState.LOGGED_IN)
		{
			columns.clear();
		}
	}

	/**
	 * Levels belong to the party they were shared with, and a different party is different people.
	 */
	@Subscribe
	public void onPartyChanged(PartyChanged event)
	{
		clientThread.invoke(specShared::clear);

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
			if (settings.partyStats())
			{
				subscribe();
				requestSync();
			}
			else
			{
				unsubscribe();
				clientThread.invoke(specShared::clear);
			}
		}
	}

	/**
	 * A party member saying what their stats are at. The stats themselves are read off the member when a
	 * column is made from them, so all this takes from the message is that a special attack bar has been
	 * heard of: an empty bar reads as nought, and so does a bar nobody has said anything about.
	 */
	@Subscribe
	public void onPartyData(PartyData event)
	{
		if (event.getSpecEnergy() == null)
		{
			return;
		}

		long memberId = event.getMemberId();

		clientThread.invoke(() -> specShared.add(memberId));
	}

	@Subscribe
	public void onUserPart(UserPart event)
	{
		clientThread.invoke(() -> specShared.remove(event.getMemberId()));
	}

	/**
	 * Asks the client to keep the stats up to date, which is what has them sent around the party as
	 * well: what is asked for here is what is sent for us and what arrives for everyone else, so this
	 * is what makes the party half of the plugin work, whoever in it is running which plugin.
	 */
	private void subscribe()
	{
		partyDataService.subscribe(this, PARTY_TYPES);
		partyDataService.subscribe(this, PARTY_SKILLS);
	}

	private void unsubscribe()
	{
		partyDataService.unsubscribe(this, PARTY_TYPES);
		partyDataService.unsubscribe(this, PARTY_SKILLS);
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

			if (boosted[i] == CombatStat.UNKNOWN || real[i] == CombatStat.UNKNOWN)
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

	/**
	 * Our own stats, read off the client.
	 */
	private void own(int[] boosted, int[] real)
	{
		for (int i = 0; i < STATS.length; i++)
		{
			CombatStat stat = STATS[i];

			set(boosted, real, i, now(stat), full(stat));
		}
	}

	/**
	 * A party member's stats, as the party has them. Only what somebody's own plugins ask for is sent
	 * for them, so a stat nobody is keeping up to date arrives as nought, and nought is a level nobody
	 * is at. The two that really do run down to nothing are points rather than a level, and their level
	 * still has to have arrived for there to be anything to draw.
	 */
	private void shared(PartyMember member, int[] boosted, int[] real)
	{
		boolean spec = specShared.contains(member.getMemberId());

		for (int i = 0; i < STATS.length; i++)
		{
			CombatStat stat = STATS[i];

			if (stat.isPercent())
			{
				// A bar is full at the same amount for everyone, so it is known as soon as the amount is
				set(boosted, real, i, spec ? member.getSpecEnergy() : CombatStat.UNKNOWN, CombatStat.FULL_PERCENT);
				continue;
			}

			set(boosted, real, i, member.getStatBoost(stat.getSkill()), member.getStatBase(stat.getSkill()));
		}
	}

	/**
	 * One stat of a set, taken as read only where both halves of it are somewhere the stat really sits.
	 * A level that has not arrived is nought, whether it is the game that has yet to send it or its owner
	 * that has yet to share it, and nought is a level nobody is at: taken for a drain, it reads as a stat
	 * however far below itself until the rest of it turns up. Hitpoints, prayer and the special attack
	 * bar are points rather than a level and do run down to nothing, so for those the amount left is
	 * taken as it comes, and it is their level that has to have arrived for there to be anything to draw.
	 */
	private static void set(int[] boosted, int[] real, int index, int now, int level)
	{
		CombatStat stat = STATS[index];
		boolean known = stat.isPossibleReal(level)
			&& (stat.isPoints() ? stat.isPossibleNow(now) : now >= 1);

		boosted[index] = known ? now : CombatStat.UNKNOWN;
		real[index] = known ? level : CombatStat.UNKNOWN;
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
