package com.github.mrebhan.crogamp.gm;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.github.mrebhan.crogamp.IProgressTracker;
import com.github.mrebhan.crogamp.ProgressTrackerExt;
import com.github.mrebhan.crogamp.cli.CommandRegistry;
import com.github.mrebhan.crogamp.cli.TableList;
import com.github.mrebhan.crogamp.settings.Settings;

import de.marco_rebhan.encodelib.IOStream;

@SuppressWarnings("deprecation")
public class GameLibrary {

	private static Settings settings;

	private static GameSettings currentGame;

	private static boolean indexFiles(IProgressTracker tracker) {
		File dir = new File(currentGame.getValue(GameSettings.PATH));
		File db = new File(dir, ".crogamp");

		db.mkdirs();
		if (dir.isDirectory()) {
			ModSettings ms = new ModSettings();
			ms.setValue(ModSettings.BASEGAME, true);
			ms.setValue(ModSettings.ID, " <base game> ");
			ms.setValue(ModSettings.PRIO, -1);
			ms.setValue(ModSettings.ENABLED, true);
			Set<File> files = new HashSet<>();
			Set<File> dirs = new HashSet<>();
			recursiveList(dir, db, files, dirs);
			tracker.update(10, 0, 0, true, files.size() + "", dirs.size() + "");
			dirs.forEach(f -> ms.getValue(ModSettings.DIRS).add('/' + dir.toURI().relativize(f.toURI()).getPath()));

			for (File file : files) {
				String p = '/' + dir.toURI().relativize(file.toURI()).getPath();
				try (InputStream is = new FileInputStream(file)) {
					byte[] sha1 = getSHA1(is);
					File targetRes = currentGame.resource(sha1);
					targetRes.getParentFile().mkdirs();
					if (!targetRes.isFile()) {
						Files.copy(file.toPath(), targetRes.toPath());
					}
					Files.delete(file.toPath());
					Files.createLink(file.toPath(), targetRes.toPath());
					ms.getValue(ModSettings.FILES).put(p, sha1);
				} catch (NoSuchAlgorithmException e) {
					throw new RuntimeException(e);
				} catch (IOException e) {
					tracker.update(-10, 0, 0, true, file.toString());
					e.printStackTrace();
					return false;
				}
			}

			currentGame.getValue(GameSettings.MODS).put(ms.getValue(ModSettings.ID), ms);

		} else {
			// this should never happen
			throw new IllegalStateException("Game folder does not exist!");
		}
		return true;
	}

	private static void recursiveList(File dir, File db, Set<File> files, Set<File> dirs) {
		Arrays.asList(dir.listFiles()).forEach(f -> {
			if (f.isFile()) {
				files.add(f);
			} else if (f.isDirectory() && !f.equals(db)) {
				dirs.add(f);
				recursiveList(f, db, files, dirs);
			}
		});
	}

	public static Settings getSettings() {
		return settings;
	}

	public static GameSettings getSelectedGame() {
		return currentGame;
	}

	public static void registerCommands(CommandRegistry reg) {
		reg.registerCommand("ga", "Adds the specified game.", "<id> <description> <path>", GameLibrary::addGame);
		reg.registerCommand("gl", "Lists all currently added games", "[pattern]", GameLibrary::listGames);
		reg.registerCommand("gs", "Selects the specified game.", "<id>", GameLibrary::selectGame);
		reg.registerCommand("gr", "Rebuilds the files of the currently active game.", "[base-only]",
				GameLibrary::rebuildGameFiles);
		reg.registerCommand("ma", "Adds a mod to the currently active game.", "<id> <file>", GameLibrary::addMod);
		reg.registerCommand("ml", "Lists all mods for the currently active game.", "[pattern]", GameLibrary::listMods);
		reg.registerCommand("mm", "Moves the specified mod to the specified position in the priority list.",
				"<id> <position>", GameLibrary::moveMod);
		reg.registerCommand("mt", "Toggles if the selected mod(s) are active.", "<pattern>...", GameLibrary::toggleMod);
		reg.registerCommand("md", "Deletes the specified mod and removes all associated files.", "<id>",
				GameLibrary::deleteMod);
	}

	private static int listGames(String[] args) {
		TableList tl = new TableList(2, "Game ID", "Full Name").sortBy(1)
				.withUnicode(settings.getValue(Settings.UNICODE));
		if (args.length > 0) {
			String s = String.join(" ", args).replace("?", ".?").replace("*", ".*?");
			tl.filterBy(0, s);
		}
		Map<String, GameSettings> l = settings.getValue(Settings.GAMES);
		l.forEach((id, settings) -> tl.addRow(id, settings.getValue(GameSettings.FULL_NAME)));
		tl.print();
		return 0;
	}

	private static int listMods(String[] args) {
		if (a()) {
			TableList tl = new TableList(3, "Position", "Mod ID", "Enabled")
					.withUnicode(settings.getValue(Settings.UNICODE))
					.compareWith((o1, o2) -> Integer.parseInt(o1[0]) - Integer.parseInt(o2[0]));
			if (args.length > 0) {
				String s = String.join(" ", args).replace("?", ".?").replace("*", ".*?");
				tl.filterBy(1, s);
			}
			currentGame.getValue(GameSettings.MODS)
					.forEach((name, ms) -> tl.addRow(Integer.toString(ms.getValue(ModSettings.PRIO)), name,
							ms.getValue(ModSettings.ENABLED) ? "Yes" : "No"));
			tl.print();
			return 0;
		} else {
			return -1;
		}
	}

	private static int selectGame(String[] args) {
		if (args.length == 1) {
			GameSettings gs = settings.getValue(Settings.GAMES).get(args[0]);
			if (gs == null) {
				System.err.printf("Game %s not registered!", args[0]);
				return -3;
			}
			selectGame(gs);
			return 0;
		}
		return -2;
	}

	public static void selectGame(GameSettings gs) {
		currentGame = gs;
	}

	private static int addGame(String[] args) {
		if (args.length == 3) {
			String id = args[0];
			String desc = args[1];
			String path = args[2];
			File f = new File(path);

			ProgressTrackerExt pt = new ProgressTrackerExt((i, d1, d2, ne, strs) -> {
				switch (i) {
				case -1:
					System.err.printf("Another game with id %s already exists!%n", strs);
					break;
				case -2:
					System.err.printf("Game directory %s does not exist! Aborting.", strs);
					break;
				case 10:
					System.out.printf("%s files, %s directories%nIndexing...%n", strs);
					break;
				case -10:
					System.err.printf("Couldn't access file %s! Aborting.%n", strs);
					break;
				}
			});

			addGame(id, desc, f, pt);
			if (pt.lastType() < 0) {
				return pt.lastType() - 2;
			}

			return 0;
		}
		return -2;
	}

	public static void addGame(String id, String desc, File root, IProgressTracker tracker) {
		if (settings.getValue(Settings.GAMES).containsKey(id)) {
			tracker.update(-1, 0, 0, true, id);
			return;
		}
		if (!root.isDirectory()) {
			tracker.update(-2, 0, 0, true, root.toString());
			return;
		}
		GameSettings gs = new GameSettings();
		gs.setValue(GameSettings.ID, id);
		gs.setValue(GameSettings.FULL_NAME, desc);
		gs.setValue(GameSettings.PATH, root.toString());
		settings.getValue(Settings.GAMES).put(id, gs);
		currentGame = gs;
		if (!indexFiles(tracker)) {
			currentGame = null;
			settings.getValue(Settings.GAMES).remove(id);
		}
	}

	private static int addMod(String[] args) {
		if (a() && args.length == 2) {
			String id = args[0];
			String path = args[1];
			File file = new File(path);

			ProgressTrackerExt pt = new ProgressTrackerExt((i, d1, d2, ne, strs) -> {
				switch (i) {
				case -1:
					System.out.printf("Another mod with id %s already exists!%nRemove it first if this is an update!%n",
							strs);
					break;
				case -2:
					System.out.printf("File %s could not be found.%n", strs);
					break;
				case 12:
					System.out.printf("%s files, %s directories%n", strs);
					break;
				}
			});

			addMod(id, file, pt);
			if (pt.lastType() < 0) {
				return pt.lastType() - 2;
			}
			return 0;
		} else {
			return -2;
		}
	}

	public static void addMod(String id, File f, IProgressTracker tracker) {
		Map<String, ModSettings> modList = currentGame.getValue(GameSettings.MODS);
		if (modList.containsKey(id)) {
			tracker.update(-1, 0, 0, true, id);
			return;
		}
		ModSettings ms = new ModSettings();
		ms.setValue(ModSettings.ID, id);
		ms.setValue(ModSettings.PRIO, 2147483647);
		ms.setValue(ModSettings.ENABLED, true);
		if (!f.isFile()) {
			tracker.update(-2, 0, 0, true, f);
			return;
		}
		boolean success = tryZip(ms, f, tracker);
		if (success) {
			modList.put(id, ms);
			currentGame.rebuildPriorities();
			b();
		}
	}

	private static int moveMod(String[] args) {
		if (a() && args.length == 2) {
			currentGame.rebuildPriorities();
			String modid = args[0];
			int newprio;
			try {
				newprio = Math.max(0, Integer.parseInt(args[1]));
			} catch (NumberFormatException e) {
				System.out.printf("%s is not a number.%n", args[1]);
				return -4;
			}
			Map<String, ModSettings> modList = currentGame.getValue(GameSettings.MODS);
			if (!modList.containsKey(modid)) {
				System.out.printf("Mod %s not registered.%n", modid);
				return -3;
			}
			ModSettings ms = modList.get(modid);
			int curprio = ms.getValue(ModSettings.PRIO);
			// -1 for up, 0 for no action, 1 for down
			int dir = (int) Math.signum(newprio - curprio);
			if (dir == 0) {
				return 0;
			}
			ArrayList<ModSettings> tm = new ArrayList<>();
			modList.forEach((id, settings) -> {
				int p = settings.getValue(ModSettings.PRIO);
				if (p <= Math.max(curprio, newprio) && p >= Math.min(curprio, newprio) && settings != ms) {
					tm.add(settings);
				}
			});
			tm.forEach(settings -> settings.setValue(ModSettings.PRIO, settings.getValue(ModSettings.PRIO) - dir));
			ms.setValue(ModSettings.PRIO, newprio);
			currentGame.rebuildPriorities();
			b();
			return 0;
		} else {
			return -2;
		}
	}

	private static int toggleMod(String[] args) {
		if (a() && args.length > 0) {
			HashSet<ModSettings> mods = new HashSet<>();
			for (String modid : args) {
				String s = modid.replace("?", ".?").replace("*", ".*?");
				Pattern p = Pattern.compile(s);
				Map<String, ModSettings> modList = currentGame.getValue(GameSettings.MODS);
				boolean hc = false;
				for (ModSettings modSettings : modList.values()) {
					Matcher m = p.matcher(modSettings.getValue(ModSettings.ID));
					if (m.matches()) {
						hc = true;
						mods.add(modSettings);
					}
				}
				if (!hc) {
					System.out.printf("Mod %s not registered.%n", modid);
				}
			}
			mods.forEach(ms -> {
				if (ms.getValue(ModSettings.BASEGAME)) {
					System.out.println("Cannot disable the base game files!");
					return;
				}
				boolean flag;
				ms.setValue(ModSettings.ENABLED, flag = !ms.getValue(ModSettings.ENABLED));
				System.out.printf("%s is now %s.%n", ms.getValue(ModSettings.ID), flag ? "enabled" : "disabled");
			});
			b();
			return 0;
		} else {
			return -2;
		}
	}

	private static int deleteMod(String[] args) {
		if (a() && args.length == 1) {
			String modid = args[0];
			Map<String, ModSettings> modList = currentGame.getValue(GameSettings.MODS);
			if (!modList.containsKey(modid)) {
				System.out.printf("Mod %s not registered.%n", modid);
				return -3;
			}
			ModSettings ms = modList.get(modid);
			if (ms.getValue(ModSettings.BASEGAME)) {
				System.out.println("Cannot delete the base game files!");
				return -4;
			}
			ms.getValue(ModSettings.FILES).forEach((name, sha1) -> {
				boolean isUsed = false;
				outerLoop: for (ModSettings mod : currentGame.getValue(GameSettings.MODS).values()) {
					if (mod == ms)
						continue;
					for (byte[] fm : mod.getValue(ModSettings.FILES).values()) {
						if (Arrays.equals(fm, sha1)) {
							isUsed = true;
							break outerLoop;
						}
					}
				}
				if (!isUsed) {
					currentGame.resource(sha1).delete();
				}
			});
			modList.remove(modid);
			currentGame.rebuildPriorities();
			b();
			return 0;
		} else {
			return -2;
		}
	}

	private static boolean tryZip(ModSettings ms, File file, IProgressTracker tracker) {
		try (ZipInputStream is = new ZipInputStream(new FileInputStream(file))) {
			ZipEntry ze;
			int f = 0;
			int d = 0;
			tracker.update(10, 0, 0, true);
			while ((ze = is.getNextEntry()) != null) {
				tracker.update(11, 0, 0, false, '/' + ze.getName());
				if (ze.isDirectory() || ze.getSize() == 0) {
					ms.getValue(ModSettings.DIRS).add(ze.getName());
					d++;
				} else {
					MessageDigest md = MessageDigest.getInstance("SHA-1");
					File tempfile = new File(".tempfile-" + Long.toHexString(System.nanoTime()));
					Files.deleteIfExists(tempfile.toPath());
					try (FileOutputStream os = new FileOutputStream(tempfile)) {
						byte[] buffer = new byte[8192];
						int len;
						while ((len = is.read(buffer)) > 0) {
							md.update(buffer, 0, len);
							os.write(buffer, 0, len);
						}
					} catch (FileNotFoundException e) {
						// this should not happen
						e.printStackTrace();
						return false;
					} catch (IOException e) {
						e.printStackTrace();
						return false;
					}
					byte[] sha1 = md.digest();
					File res = currentGame.resource(sha1);
					res.getParentFile().mkdirs();
					if (!res.exists()) {
						Files.copy(tempfile.toPath(), currentGame.resource(sha1).toPath());
					}
					Files.delete(tempfile.toPath());
					ms.getValue(ModSettings.FILES).put('/' + ze.getName(), sha1);
					f++;
				}
			}
			tracker.update(12, 0, 0, true, f, d);
			return true;
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e1) {
			e1.printStackTrace();
		} catch (NoSuchAlgorithmException e1) {
			e1.printStackTrace();
		}
		return false;
	}

	private static int rebuildGameFiles(String[] args) {
		if (a()) {
			boolean baseonly = args.length == 1 && "base-only".equals(args[0]);

			rebuildGameFiles(baseonly, (i, p, f, n, arr) -> {
				switch (i) {
				case -2:
					System.out.printf("Overwriting %s!%n", arr[0]);
					break;
				case -1:
					System.out.printf("Couldn't delete file %s%n", arr[0]);
					break;
				case 0:
					System.out.printf(" > %s%n", arr[0]);
					break;
				}
			});

			return 0;
		} else {
			return -1;
		}
	}

	public static void rebuildGameFiles(boolean baseOnly, IProgressTracker tracker) {
		// 1. Remove files
		HashSet<File> s = new HashSet<>();
		File dir = new File(currentGame.getValue(GameSettings.PATH));
		currentGame.getValue(GameSettings.MODS)
				.forEach((id, ms) -> ms.getValue(ModSettings.FILES).forEach((fs, sha) -> s.add(new File(dir, fs))));
		s.forEach(f -> {
			try {
				Files.deleteIfExists(f.toPath());
			} catch (IOException e) {
				tracker.update(-1, 0, 0, true, f.toString());
			}
		});

		// 2. Link files, sorted by priority
		ArrayList<ModSettings> mods = new ArrayList<>();
		currentGame.getValue(GameSettings.MODS).forEach((id, ms) -> mods.add(ms));
		mods.removeIf(m -> !m.getValue(ModSettings.ENABLED) || (baseOnly && !m.getValue(ModSettings.BASEGAME)));
		mods.sort((o1, o2) -> o1.getValue(ModSettings.PRIO) > o2.getValue(ModSettings.PRIO) ? 1 : -1);
		mods.forEach(m -> m.getValue(ModSettings.DIRS).forEach(d -> new File(dir, d).mkdirs()));
		mods.forEach(m -> {
			tracker.update(0, 0, 0, true, m.getValue(ModSettings.ID));
			m.getValue(ModSettings.FILES).forEach((f, sha) -> {
				try {
					File file = new File(dir, f);
					if (file.exists()) {
						tracker.update(-2, 0, 0, true, f);
						Files.deleteIfExists(file.toPath());
					}
					Files.createLink(file.toPath(), currentGame.resource(sha).toPath());
				} catch (IOException e) {
					e.printStackTrace();
				}
			});
		});
	}

	private static byte[] getSHA1(InputStream is) throws NoSuchAlgorithmException, IOException {
		MessageDigest md = MessageDigest.getInstance("SHA-1");
		int n = 0;
		byte[] buffer = new byte[8192];
		while (n != -1) {
			n = is.read(buffer);
			if (n > 0) {
				md.update(buffer, 0, n);
			}
		}
		return md.digest();
	}

	/**
	 * Checks if a game is selected or not.
	 * 
	 * @return true if a game is selected, false otherwise
	 */

	private static boolean a() {
		if (currentGame == null) {
			System.out.println("Please select a game!");
			return false;
		}
		return true;
	}

	private static void b() {
		System.out.println("Please rebuild the game to see changes made.");
	}

	static {
		IOStream stream = new IOStream(true);
		File sf = Settings.FILE;
		if (sf.exists()) {
			try (FileInputStream is = new FileInputStream(sf)) {
				byte[] buf = new byte[4096];
				int read;
				while ((read = is.read(buf)) > 0) {
					stream.putRawByte(buf, read);
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		} else {
			stream.putInt(0);
		}

		settings = new Settings();
		settings.deserialize(stream);

		currentGame = null;
	}

	private GameLibrary() {

	}

}
