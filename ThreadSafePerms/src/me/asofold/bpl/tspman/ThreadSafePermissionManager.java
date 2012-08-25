package me.asofold.bpl.tspman;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

/**
 * Allow access methods for thread safe permission queries.
 * <hr>
 * NOTE: This does not invalidate cache on world changes or logouts. Call removePlayer for if desired, on world changes for instance.
 * <hr>
 * NOTE: This is a quick attempt, the synchronization is not most intricate and probably not the fastest. Memory usage could also be optimized. 
 * <hr>
 * 
 * TODO: Check: async task for updating permissions call, if expired but within some range? 
 * 
 * @license Not sure what the use of Bukkit means , so "minimal" license, that is basically Public Domain, no warranty / fitness guarantees - if use of Bukkit API enforces more then that (possibly GPL). 
 * 
 * @author mc_dev
 *
 */
public class ThreadSafePermissionManager implements Listener{
	/**
	 * Event to query for an update of permissions.
	 * @author mc_dev
	 *
	 */
	private static class PermissionQueryEvent extends Event{
		private static final HandlerList handlers = new HandlerList();
		@Override
		public HandlerList getHandlers() {
			return handlers;
		}
		/**
		 * Must have :_) ...
		 * @return
		 */
		@SuppressWarnings("unused")
		public static HandlerList getHandlerList() {
			return handlers;
		}
		
		private final Set<String> perms = new HashSet<String>();
		private Player player;
		private Map<String, Boolean> result = new HashMap<String, Boolean>();
		
		public PermissionQueryEvent(final Player player, final Collection<String> permissions){
			this.perms.addAll(permissions);
			this.player = player;
		}
		public final Player getPlayer(){
			return player;
		}
		public final Set<String> getPermissions(){
			return perms;
		}
		public final Map<String, Boolean> getResult() {
			return result;
		}
		public final void setResult(final Map<String, Boolean> result) {
			this.result = result;
		}
	}
	
	private static final class PlayerEntry{
		public final Map<String, PermissionEntry> permissions = Collections.synchronizedMap(new HashMap<String, PermissionEntry>(20));
	}
	
	private static final class PermissionEntry{
		public long ts;
		public boolean has;
		public PermissionEntry(final boolean has, final long ts){
			this.has = has;
			this.ts = ts;
		}
	}
	
	private final Map<String, PlayerEntry> players = Collections.synchronizedMap(new HashMap<String, PlayerEntry>(20, 0.6f));
	
	private long durExpire = 10000;
	
	public ThreadSafePermissionManager(){
		// Empty constructor !
	}
	
	/**
	 * Set the expiration duration of permission entries during construction, already.
	 * @param durExpire
	 */
	public ThreadSafePermissionManager(long durExpire){
		this.durExpire = durExpire;
	}
	
	private final PlayerEntry getPlayerEntry(final Player player){
		final String playerName = player.getName();
		PlayerEntry entry = players.get(playerName);
		if (entry == null){
			entry = new PlayerEntry();
			players.put(playerName, entry);
		}
		return entry;
	}
	
	@EventHandler(priority=EventPriority.LOWEST)
	final void onQuery(final PermissionQueryEvent event){
		event.setResult(unsafeUpdatePermissions(event.getPlayer(), event.getPermissions()));
	}

	private final Map<String, Boolean> unsafeUpdatePermissions(final Player player, final Set<String> permissions) {
		final PlayerEntry playerEntry = getPlayerEntry(player);
		final Map<String, Boolean> res = new HashMap<String, Boolean>(permissions.size());
		final long ts = System.currentTimeMillis();
		final long tsExpire = ts - durExpire;
		for (final String permission : permissions){
			PermissionEntry entry = playerEntry.permissions.get(permission);
			if (entry == null){
				entry = new PermissionEntry(player.hasPermission(permission), ts);
				playerEntry.permissions.put(permission, entry);
			}
			else if (entry.ts < tsExpire){
				entry.has = player.hasPermission(permission);
				entry.ts = ts;
			}
			res.put(permission, entry.has);
		}
		return res;
	}
	
	/**
	 * Get a set with all permissions that are cached for a player, if desired including expired entries.
	 * @param player
	 * @return
	 */
	public final Set<String> getPermissionSet(final Player player, final boolean expired){
		Set<String> perms = new HashSet<String>();
		final PlayerEntry playerEntry = getPlayerEntry(player);
		synchronized (playerEntry.permissions){
			final long tsExpire = System.currentTimeMillis() - durExpire;
			for (final Entry<String, PermissionEntry> entry : playerEntry.permissions.entrySet()){
				final PermissionEntry permEntry = entry.getValue();
				if (expired || permEntry.ts >= tsExpire) perms.add(entry.getKey());
			}
		}
		return perms;
	}
	
	/**
	 * Get a map with all cached permissions, leaving out expired if desired.
	 * @param player
	 * @param expired If to include expired entries.
	 * @return
	 */
	public final Map<String, Boolean> getPermissionMap(final Player player, final boolean expired){
		Map<String, Boolean> perms = new HashMap<String, Boolean>();
		final PlayerEntry playerEntry = getPlayerEntry(player);
		synchronized (playerEntry.permissions){
			final long tsExpire = System.currentTimeMillis() - durExpire;
			for (final Entry<String, PermissionEntry> entry : playerEntry.permissions.entrySet()){
				final PermissionEntry permEntry = entry.getValue();
				if (expired || permEntry.ts >= tsExpire) perms.put(entry.getKey(), permEntry.has);
			}
		}
		return perms;
	}
	
	/**
	 * Update all expired permission entries for the player.<br>
	 * NOTE: THIS CAN ONLY BE CALLED FROM THE MAIN THREAD.
	 * @param player
	 * @return
	 */
	public final void updatePlayer(final Player player){
		final PlayerEntry playerEntry = getPlayerEntry(player);
		synchronized (playerEntry.permissions){
			final long ts = System.currentTimeMillis();
			final long tsExpire = ts - durExpire;
			for (final Entry<String, PermissionEntry> entry : playerEntry.permissions.entrySet()){
				final PermissionEntry permEntry = entry.getValue();
				if (permEntry.ts < tsExpire){
					permEntry.ts = ts;
					permEntry.has = player.hasPermission(entry.getKey());
				}
			}
		}
	}
	
	/**
	 * Update all expired given permission entries for the player, add missing entries.<br>
	 * NOTE: THIS CAN ONLY BE CALLED FROM THE MAIN THREAD.
	 * @param player
	 * @return
	 */
	public final void updatePlayer(final Player player, final Collection<String> permissions){
		final PlayerEntry playerEntry = getPlayerEntry(player);
		synchronized (playerEntry.permissions){
			final long ts = System.currentTimeMillis();
			final long tsExpire = ts - durExpire;
			for (final String perm : permissions){
				final PermissionEntry permEntry = playerEntry.permissions.get(perm);
				if (permEntry == null) playerEntry.permissions.put(perm, new PermissionEntry(player.hasPermission(perm), ts));
				else if (permEntry.ts < tsExpire){
					permEntry.ts = ts;
					permEntry.has = player.hasPermission(perm);
				}
			}
		}
	}
	
	/**
	 * Update all permissions for a player, overwrite all.<br>
	 * NOTE: THIS CAN ONLY BE CALLED FROM THE MAIN THREAD.
	 * @param player
	 */
	public final void forceUpdatePlayer(final Player player){
		final PlayerEntry playerEntry = getPlayerEntry(player);
		synchronized (playerEntry.permissions){
			final long ts = System.currentTimeMillis();
			for (final Entry<String, PermissionEntry> entry : playerEntry.permissions.entrySet()){
				final PermissionEntry permEntry = entry.getValue();
				permEntry.ts = ts;
				permEntry.has = player.hasPermission(entry.getKey());
			}
		}
	}
	
	/**
	 * Update all given permissions for a player, update missing entries, overwrite all.<br>
	 * NOTE: THIS CAN ONLY BE CALLED FROM THE MAIN THREAD.
	 * <hr>
	 * This can be used in a sync task to ensure the permissions are there for your async calls.
	 * @param player
	 * @param permissions The permissions to update.
	 */
	public final void forceUpdatePlayer(final Player player, final Collection<String> permissions){
		final PlayerEntry playerEntry = getPlayerEntry(player);
		synchronized (playerEntry.permissions){
			final long ts = System.currentTimeMillis();
			for (String perm : permissions){
				final PermissionEntry permEntry = playerEntry.permissions.get(perm);
				if (permEntry == null) playerEntry.permissions.put(perm, new PermissionEntry(player.hasPermission(perm), ts));
				else{
					permEntry.ts = ts;
					permEntry.has = player.hasPermission(perm);
				}
			}
		}
	}
	
	/**
	 * Query several permissions.
	 * <hr>
	 * This will sync into the main thread if cache entries are not present or expired, leading to up to 50 ms delay.
	 * @param player
	 * @param permissions
	 * @return
	 */
	public final Map<String, Boolean> hasPermissions(final Player player, final Collection<String> permissions){
		final PlayerEntry playerEntry = getPlayerEntry(player);
		final Set<String> notPresent = new HashSet<String>();
		final Map<String, Boolean> result = new HashMap<String, Boolean>();
		final long tsExpire = System.currentTimeMillis() - durExpire;
		for (final String perm : permissions){
			final PermissionEntry entry = playerEntry.permissions.get(perm);
			if (entry == null) notPresent.add(perm);
			else if (entry.ts < tsExpire) notPresent.add(perm);
			else result.put(perm, entry.has);
		}
		if (!notPresent.isEmpty()){
			final PermissionQueryEvent event = new PermissionQueryEvent(player, notPresent);
			Bukkit.getPluginManager().callEvent(event);
			result.putAll(event.getResult());
		}
		return result;
	}
	
	/**
	 * Query a single permission (not recommended for frequent use).
	 * <hr>
	 * This will sync into the main thread if cache entries are not present or expired, leading to up to 50 ms delay.
	 * @param player
	 * @param permission
	 * @return
	 */
	public final boolean hasPermission(final Player player, final String permission){
		// TODO: maybe make more efficient version for this.
		final List<String> perms = new LinkedList<String>();
		perms.add(permission);
		return hasPermissions(player, perms).get(permission);
	}
	
	/**
	 * Remove a players permission entries.
	 * @param player
	 * @return
	 */
	public boolean removePlayer(Player player){
		return players.remove(player.getName()) != null;
	}

	/**
	 * Get the duration in ms, for which permission entries expire.
	 * @return
	 */
	public long getDurExpire() {
		return durExpire;
	}

	/**
	 * Set the duration in ms for which entries expire.
	 * @param durExpire
	 */
	public void setDurExpire(long durExpire) {
		this.durExpire = durExpire;
	}
	
	/**
	 * Ensure the duration is minimally the given one.
	 * @param durExpire
	 * @return
	 */
	public long setMinDurExpire(long durExpire){
		return this.durExpire = Math.max(durExpire, this.durExpire);
	}
	
	/**
	 * Ensure the duration is maximally the given one.
	 * @param durExpire
	 * @return
	 */
	public long setMaxDurExpire(long durExpire){
		return this.durExpire = Math.min(durExpire, this.durExpire);
	}
	
}
