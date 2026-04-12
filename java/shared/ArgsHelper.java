package shared;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;
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

	/** Tries to load properties from the given file name */
	public Properties loadProperties(String configPath, boolean exitWhenFailed)
			throws IOException, FileNotFoundException {
		var props = new Properties();
		if (configPath != null) {
			Path path = Path.of(configPath);
			if (!Files.exists(path)) {
				log("Error: config not found: %", path);
				if (exitWhenFailed) {
					System.exit(1);
				}
			}
			try (var r = new java.io.InputStreamReader(new FileInputStream(path.toFile()), StandardCharsets.UTF_8)) {
				props.load(r);
			}
		}
		return props;
	}

	public void listProperties(Map<String, String> cli) {
		logRaw("");
		logRaw("| Configuration (%s)",  namesspace);
		logRaw("| ");
		int maxLen = cli.keySet().stream().mapToInt(ky -> ky.length()).max().orElse(0);
		var format = "|   %-" + maxLen + "s : %s";
		for (var en : cli.entrySet()) {
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
		return cli.getOrDefault(key, def);
	}

	public String resolveUC(Map<String, String> cli, String key, String def) {
		String val = resolve(cli, key, def);
		return val == null ? null : val.toUpperCase();
	}

	public Integer resolveInt(Map<String, String> cli, String key, Integer def) {
		String val = resolve(cli, key, null);
		if (val == null || val.isBlank())
			return def;

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
		if (val == null || val.isBlank())
			return def;

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
			logRaw(msg);
		} else {
			logRaw(toolName + ": " + msg, objects);
		}
	}

	private void logRaw(String msg, Object... objects) {
		logger.accept(String.format(msg, objects));
	}

}
