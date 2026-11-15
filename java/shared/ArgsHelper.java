package shared;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class ArgsHelper {

	private final String toolName;
	private final String namesspace;
	private final Consumer<String> logger;
	private final Supplier<String> usage;

	public ArgsHelper(String toolName, String namesspace, Consumer<String> logger, Supplier<String> usage) {
		super();

		if (namesspace.endsWith(".")) {
			namesspace = namesspace.substring(0, namesspace.lastIndexOf('.') - 1);
		}

		this.toolName = toolName;
		this.namesspace = namesspace;
		this.logger = logger;
		this.usage = usage;
	}

	/**
	 * Parses the command line into a map of option key → value. Every option takes
	 * exactly one value.
	 *
	 * @param optionKey maps a word to its option key, or {@code null} when the word
	 *                  is not an option of the running tool
	 *
	 *                  Exits with code 1 (after logging the error and the usage)
	 *                  when:
	 *                  <ul>
	 *                  <li>a word is not an option of the running tool;</li>
	 *                  <li>an option has no value: it is the last word, or the
	 *                  next word is itself an option of the running tool.</li>
	 *                  </ul>
	 *                  A lone {@code -} (stdin/stdout) is never an option, so it is
	 *                  accepted as a value.
	 */
	public static Map<String, String> parse(String[] args, Function<String, String> optionKey,
			Consumer<String> logger, Supplier<String> usage) {
		var map = new LinkedHashMap<String, String>();
		for (int i = 0; i < args.length; i++) {
			String key = optionKey.apply(args[i]);
			if (key == null) {
				fail(logger, usage, String.format("Error: unknown option '%s'", args[i]));
			}
			if (i + 1 >= args.length) {
				fail(logger, usage, String.format("Error: option '%s' requires a value", args[i]));
			}
			if (optionKey.apply(args[i + 1]) != null) {
				fail(logger, usage,
						String.format("Error: option '%s' requires a value, got option '%s'", args[i], args[i + 1]));
			}
			map.put(key, args[++i]);
		}
		return map;
	}

	private static void fail(Consumer<String> logger, Supplier<String> usage, String msg) {
		logger.accept(msg);
		if (usage != null) {
			logger.accept(usage.get());
		}
		System.exit(1);
	}

	/**
	 * File or directory name of {@code path} without directories and without its
	 * last extension, e.g. {@code data/out/kaikki-pt.dic} → {@code kaikki-pt},
	 * {@code data/out/kaikki-pt.d/} → {@code kaikki-pt}. Used as a default
	 * dictionary name.
	 */
	public static String baseName(String path) {
		Path fileName = Path.of(path).getFileName();
		String name = fileName == null ? path : fileName.toString();
		int dot = name.lastIndexOf('.');
		return dot > 0 ? name.substring(0, dot) : name;
	}

	/** Tries to load properties from the given file name */
	public Properties loadProperties(String configPath, boolean exitWhenFailed)
			throws IOException, FileNotFoundException {
		var props = new Properties();
		if (configPath != null) {
			Path path = Path.of(configPath);
			if (!Files.exists(path)) {
				log("Error: config not found: %s", path);
				if (exitWhenFailed) {
					System.exit(1);
				}
				return props;
			}
			try (var r = new java.io.InputStreamReader(new FileInputStream(path.toFile()), StandardCharsets.UTF_8)) {
				props.load(r);
			}
		}
		return props;
	}

	public void listProperties(Map<String, String> cli) {
		var ord = new LinkedHashMap<>(cli); // ordered
		logRaw("");
		logRaw("| Configuration (%s)", namesspace);
		logRaw("| ");
		int maxLen = ord.keySet().stream().mapToInt(ky -> ky.length()).max().orElse(0);
		var format = "|   %-" + maxLen + "s : %s";
		for (var en : ord.entrySet()) {
			logRaw(format, en.getKey(), en.getValue());
		}
		logRaw("");
	}

	/** Merge props into cli (cli wins) */
	public void mergeConfig2Cli(List<String> myArgs, Map<String, String> cli, Properties config) {

		String ns = namesspace + ".";

		for (String key : myArgs) {
			if (!cli.containsKey(key)) {
				String val = config.getProperty(ns + key);
				if (val == null) {
					val = config.getProperty(key);
				}
				if (val != null) {
					cli.put(key, val);
				}
			}
		}
	}

	public String require(Map<String, String> cli, String key, String desc) {
		String val = cli.get(key);
		if (val == null) {
			log("Error: missing required argument %s", desc);
			if (usage != null) {
				log(usage.get());
			}
			System.exit(1);
		}
		return val;
	}

	public String requireUC(Map<String, String> cli, String key, String desc) {
		String val = require(cli, key, desc);
		return val == null ? null : val.toUpperCase();
	}

	public Integer requireInt(Map<String, String> cli, String key, String desc) {
		String val = require(cli, key, desc);
		try {
			return Integer.parseInt(val);
		} catch (Exception e) {
			log("Invalid integer value for '%s': %s", key, val);
			System.exit(1);
			return null;
		}
	}

	public Boolean requireBool(Map<String, String> cli, String key, String desc) {
		String val = require(cli, key, desc);
		return switch (val.toLowerCase()) {
		case "true" -> true;
		case "false" -> false;
		default -> {
			log("Invalid boolean value for '%s': %s", key, val);
			System.exit(1);
			yield null;
		}
		};
	}

	public String resolve(Map<String, String> cli, String key, String def) {
		return cli.computeIfAbsent(key, k -> def);
	}

	public String resolveUC(Map<String, String> cli, String key, String def) {
		String val = resolve(cli, key, def);
		return val == null ? null : val.toUpperCase();
	}

	public Integer resolveInt(Map<String, String> cli, String key, Integer def) {
		String val = resolve(cli, key, null);
		if (val == null || val.isBlank()) {
			cli.put(key, def == null ? null : def.toString());
			return def;
		}

		try {
			return Integer.parseInt(val);
		} catch (Exception e) {
			log("Invalid integer value for '%s': %s", key, val);
			System.exit(1);
			return null;
		}
	}

	public Boolean resolveBool(Map<String, String> cli, String key, Boolean def) {
		String val = resolve(cli, key, null);
		if (val == null || val.isBlank()) {
			cli.put(key, def == null ? null : def.toString());
			return def;
		}

		return switch (val.toLowerCase()) {
		case "true" -> true;
		case "false" -> false;
		default -> {
			log("Invalid boolean value for '%s': %s", key, val);
			System.exit(1);
			yield null;
		}
		};
	}

	void log(String msg, Object... objects) {
		if (msg.isBlank() || msg.startsWith(toolName)) {
			logRaw(msg, objects);
		} else {
			logRaw(toolName + ": " + msg, objects);
		}
	}

	/**
	 * Formats only when arguments are given (pre-built messages may contain a
	 * literal '%'). The logger receives the final text and must not format it
	 * again.
	 */
	private void logRaw(String msg, Object... objects) {
		logger.accept(objects.length == 0 ? msg : String.format(msg, objects));
	}

}
