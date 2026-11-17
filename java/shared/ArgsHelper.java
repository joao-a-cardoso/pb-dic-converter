package shared;

import static shared.TextHelper.lower;
import static shared.TextHelper.upper;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class ArgsHelper {

	private final String toolName;
	private final String namespace;
	private final Consumer<String> logger;
	private final Supplier<String> usage;

	/**
	 * Option keys whose values are file or folder paths (shown with
	 * {@link #displayPath}).
	 */
	private static final java.util.Set<String> PATH_KEYS = java.util.Set.of("in", "out", "config", "langdir",
			"terms-file", "xdxf", "dic");

	/**
	 * A path as shown in messages: relative to the current folder when inside it,
	 * {@code <temp>/…} when inside the system temp folder, otherwise absolute.
	 * {@code null} and {@code -} (stdin/stdout) are returned unchanged. Only for
	 * display — the tools always use the real path.
	 */
	public static String displayPath(String path) {
		if (path == null || path.equals("-")) {
			return path;
		}
		try {
			Path abs = Path.of(path).toAbsolutePath().normalize();
			Path cwd = Path.of("").toAbsolutePath().normalize();
			Path tmp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
			if (abs.startsWith(cwd)) {
				String rel = cwd.relativize(abs).toString();
				return rel.isEmpty() ? "." : rel;
			}
			if (abs.startsWith(tmp)) {
				return "<temp>/" + tmp.relativize(abs);
			}
			return abs.toString();
		} catch (RuntimeException e) {
			return path; // not a valid path: show as given
		}
	}

	/** Where each option value came from, shown by {@link #listProperties}. */
	private final Map<String, String> sources = new java.util.HashMap<>();

	private static final String FROM_CLI = "command line", FROM_CONFIG = "config", FROM_DEFAULT = "default";

	public ArgsHelper(String toolName, String namespace, Consumer<String> logger, Supplier<String> usage) {
		super();

		// accept "tab2xdxf" or "tab2xdxf." — the "." separator is added when keys are
		// looked up
		while (namespace.endsWith(".")) {
			namespace = namespace.substring(0, namespace.length() - 1);
		}

		this.toolName = toolName;
		this.namespace = namespace;
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
	 *                  <li>an option has no value: it is the last word, or the next
	 *                  word is itself an option of the running tool.</li>
	 *                  </ul>
	 *                  A lone {@code -} (stdin/stdout) is never an option, so it is
	 *                  accepted as a value.
	 *
	 *                  {@code -h} or {@code --help} anywhere on the command line
	 *                  prints the usage to stdout and exits with code 0 (usage
	 *                  shown because of an error goes to the logger, i.e. stderr).
	 */
	public static Map<String, String> parse(String[] args, Function<String, String> optionKey, Consumer<String> logger,
			Supplier<String> usage) {
		for (String arg : args) {
			if (arg.equals("-h") || arg.equals("--help")) {
				throw CliException.help();
			}
		}
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
			String value = args[++i];
			if (key.equals(DEF_OPTION) && map.containsKey(key)) {
				value = map.get(key) + '\0' + value; // repeatable: collect all --def values
			}
			map.put(key, value);
		}
		return map;
	}

	private static void fail(Consumer<String> logger, Supplier<String> usage, String msg) {
		throw CliException.usageError(msg);
	}

	/**
	 * Prints the usage text to {@code out}: {@code System.out} when help was
	 * requested (-h / --help), {@code System.err} when shown because of an error.
	 * The usage text starts with the tool name, so it is printed as is.
	 *
	 * @throws NullPointerException if {@code out} is null (a programming error)
	 */
	public static void displayUsage(Supplier<String> usage, PrintStream out) {
		Objects.requireNonNull(out, "displayUsage: out must not be null");
		if (usage != null) {
			out.println(usage.get());
		}
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

	/**
	 * Loads a config file ({@code .properties} format) with two extensions:
	 * <ul>
	 * <li><b>End-of-line comments:</b> a {@code #} preceded by a space or tab
	 * starts a comment ({@code tab2xdxf.lang=pt   # Portuguese}). A {@code #}
	 * without whitespace before it is kept ({@code C#}); write {@code \#} for a
	 * literal {@code #} after a space. Lines starting with {@code #} are comments,
	 * as usual.</li>
	 * <li><b>Variables:</b> {@code def lang=pt} defines a variable, used in values
	 * as {@code $lang} or {@code ${lang}} ({@code $$} = a literal {@code $}).
	 * Variables may use other variables. An undefined variable, a variable defined
	 * twice or a definition loop is an error (exit code 1).</li>
	 * </ul>
	 * Then checks the key style (see {@link #checkKeyStyle}).
	 */
	public Properties loadProperties(String configPath, boolean exitWhenFailed)
			throws IOException, FileNotFoundException {
		return loadProperties(configPath, exitWhenFailed, Map.of());
	}

	/**
	 * Option key of {@code --def <name>=<value>}; the only option that may be
	 * repeated.
	 */
	public static final String DEF_OPTION = "def";

	private static final java.util.regex.Pattern CLI_DEF = java.util.regex.Pattern.compile("([A-Za-z0-9_]+)=(.*)",
			java.util.regex.Pattern.DOTALL);

	/**
	 * Loads the config given with {@code -c} ({@code cli "config"}), with the
	 * variables given with {@code --def <name>=<value>} replacing the config's
	 * {@code def} of the same name (or adding a new variable). The command-line
	 * variables are listed as {@code def <name>} in the configuration listing. A
	 * malformed {@code --def} or a name given twice is an error (exit code 1).
	 */
	public Properties loadConfig(Map<String, String> cli) throws IOException {
		cli.keySet().forEach(k -> sources.put(k, FROM_CLI)); // parsed from the command line
		var cliDefs = new LinkedHashMap<String, String>();
		String raw = cli.remove(DEF_OPTION);
		if (raw != null) {
			for (String def : raw.split("\0")) {
				var m = CLI_DEF.matcher(def);
				if (!m.matches()) {
					throw CliException.error("Error: invalid --def '%s', expected: --def <name>=<value>", def);
				}
				String name = m.group(1);
				if (cliDefs.containsKey(name)) {
					throw CliException.error("Error: variable '%s' given twice with --def", name);
				}
				cliDefs.put(name, m.group(2));
				cli.put("def " + name, m.group(2));
				sources.put("def " + name, FROM_CLI);
			}
		}
		String configPath = cli.get("config");
		if (configPath == null && !cliDefs.isEmpty()) {
			log("Warning: --def has no effect without a config (-c), ignoring.");
		}
		return loadProperties(configPath, true, cliDefs);
	}

	/**
	 * As {@link #loadProperties(String, boolean)}, with {@code cliDefs} overriding
	 * the config's variables.
	 */
	public Properties loadProperties(String configPath, boolean exitWhenFailed, Map<String, String> cliDefs)
			throws IOException, FileNotFoundException {
		var props = new Properties();
		if (configPath != null) {
			Path path = Path.of(configPath);
			if (!Files.exists(path)) {
				if (exitWhenFailed) {
					throw CliException.error("Error: config not found: %s", path);
				}
				log("Error: config not found: %s", path);
				return props;
			}
			var defs = new LinkedHashMap<String, String>();
			var body = new StringBuilder();
			int lineNo = 0;
			for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
				lineNo++;
				line = stripComment(line);
				var def = DEF_LINE.matcher(line);
				if (def.matches()) {
					String name = def.group(1);
					if (defs.containsKey(name)) {
						configError(path, "variable '%s' defined twice (line %d)", name, lineNo);
					}
					defs.put(name, def.group(2).strip());
				} else if (line.strip().startsWith("def ") || line.strip().startsWith("def\t")) {
					configError(path, "invalid definition at line %d, expected: def <name>=<value>", lineNo);
				} else {
					body.append(line).append('\n');
				}
			}
			props.load(new java.io.StringReader(body.toString()));
			defs.putAll(cliDefs); // --def on the command line wins over the config's def

			// resolve the variables, then substitute them in every value
			var resolved = new LinkedHashMap<String, String>();
			for (String name : defs.keySet()) {
				resolveVar(path, name, defs, resolved, new java.util.ArrayDeque<>());
			}
			for (String key : props.stringPropertyNames()) {
				props.setProperty(key, substitute(path, props.getProperty(key), "'" + key + "'", defs, resolved,
						new java.util.ArrayDeque<>()));
			}
			checkKeyStyle(path, props);
		}
		return props;
	}

	/** {@code def <name>=<value>}; name: letters, digits, '_'. */
	private static final java.util.regex.Pattern DEF_LINE = java.util.regex.Pattern
			.compile("\\s*def[ \\t]+([A-Za-z0-9_]+)\\s*=(.*)");

	/** {@code $$}, {@code ${name}} or {@code $name}. */
	private static final java.util.regex.Pattern VAR_REF = java.util.regex.Pattern
			.compile("\\$(?:(\\$)|\\{([A-Za-z0-9_]+)\\}|([A-Za-z0-9_]+))");

	/** Removes an end-of-line comment: a '#' preceded by a space or tab. */
	static String stripComment(String line) {
		for (int i = 1; i < line.length(); i++) {
			char c = line.charAt(i - 1);
			if (line.charAt(i) == '#' && (c == ' ' || c == '\t')) {
				return line.substring(0, i).stripTrailing();
			}
		}
		return line;
	}

	private String resolveVar(Path path, String name, Map<String, String> defs, Map<String, String> resolved,
			java.util.Deque<String> stack) {
		if (resolved.containsKey(name)) {
			return resolved.get(name);
		}
		if (stack.contains(name)) {
			var loop = new java.util.ArrayList<>(stack);
			java.util.Collections.reverse(loop);
			loop.add(name);
			configError(path, "variable loop: %s", String.join(" -> ", loop));
		}
		stack.push(name);
		String value = substitute(path, defs.get(name), "def " + name, defs, resolved, stack);
		stack.pop();
		resolved.put(name, value);
		return value;
	}

	private String substitute(Path path, String value, String where, Map<String, String> defs,
			Map<String, String> resolved, java.util.Deque<String> stack) {
		var m = VAR_REF.matcher(value);
		var out = new StringBuilder();
		while (m.find()) {
			String replacement;
			if (m.group(1) != null) {
				replacement = "$";
			} else {
				String name = m.group(2) != null ? m.group(2) : m.group(3);
				if (!defs.containsKey(name)) {
					configError(path, "undefined variable '$%s' in %s", name, where);
				}
				replacement = resolveVar(path, name, defs, resolved, stack);
			}
			m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(replacement));
		}
		m.appendTail(out);
		return out.toString();
	}

	private void configError(Path path, String msg, Object... args) {
		throw CliException.error("Error: config %s: %s", path, String.format(msg, args));
	}

	/** A key is prefixed when it has a namespace, e.g. {@code tab2xdxf.in}. */
	private static boolean isPrefixed(String key) {
		return key.contains(".");
	}

	/**
	 * A config uses either only bare keys ({@code in=}, for a single tool) or only
	 * prefixed keys ({@code tab2xdxf.in=}, shareable by several tools). Mixing them
	 * is an error: a bare key in a shared config would be taken by every tool. Logs
	 * the bare keys and exits with code 1.
	 */
	private void checkKeyStyle(Path path, Properties props) {
		var names = props.stringPropertyNames();
		if (names.stream().anyMatch(ArgsHelper::isPrefixed)) {
			var bare = names.stream().filter(k -> !isPrefixed(k)).sorted().toList();
			if (!bare.isEmpty()) {
				throw CliException.error("Error: config %s mixes prefixed and bare keys; add a prefix to: %s", path,
						String.join(", ", bare));
			}
		}
	}

	public void listProperties(Map<String, String> cli) {
		var ord = new LinkedHashMap<>(cli); // ordered
		logRaw("");
		logRaw("| Configuration (%s)", namespace);
		logRaw("| ");
		int maxLen = ord.keySet().stream().mapToInt(ky -> ky.length()).max().orElse(0);
		int maxVal = ord.entrySet().stream()
				.map(e -> PATH_KEYS.contains(e.getKey()) ? displayPath(e.getValue()) : e.getValue())
				.mapToInt(v -> String.valueOf(v).length()).max().orElse(0);
		var format = "|   %-" + maxLen + "s : %-" + Math.min(maxVal, 60) + "s   %s";
		// options first, then the --def variables ("def <name>"), separated by an empty
		// line
		boolean defsStarted = false;
		for (boolean defs : new boolean[] { false, true }) {
			for (var en : ord.entrySet()) {
				if (en.getKey().startsWith("def ") != defs) {
					continue;
				}
				if (defs && !defsStarted) {
					logRaw("| ");
					defsStarted = true;
				}
				String src = sources.get(en.getKey());
				String val = PATH_KEYS.contains(en.getKey()) ? displayPath(en.getValue()) : en.getValue();
				logRaw("%s",
						String.format(format, en.getKey(), val, src == null ? "" : "(" + src + ")").stripTrailing());
			}
		}
		logRaw("");
	}

	/**
	 * Merge config values into cli (cli wins). A prefixed config (see
	 * {@link #checkKeyStyle}) is read through this tool's namespace only
	 * ({@code tab2xdxf.in}); keys of other namespaces are ignored, keys of this
	 * namespace that are not in {@code myArgs} are reported as unknown. A config
	 * with only bare keys ({@code in}) is read as is.
	 */
	public void mergeConfig2Cli(List<String> myArgs, Map<String, String> cli, Properties config) {

		String ns = namespace + ".";
		boolean prefixed = config.stringPropertyNames().stream().anyMatch(ArgsHelper::isPrefixed);

		if (prefixed) {
			config.stringPropertyNames().stream().filter(k -> k.startsWith(ns))
					.filter(k -> !myArgs.contains(k.substring(ns.length()))).sorted()
					.forEach(k -> log("Warning: unknown config key '%s', ignoring.", k));
		}

		for (String key : myArgs) {
			if (!cli.containsKey(key)) {
				String val = config.getProperty(prefixed ? ns + key : key);
				if (val != null) {
					cli.put(key, val);
					sources.put(key, FROM_CONFIG);
				}
			}
		}
	}

	public String resolve(Map<String, String> cli, String key, String def) {
		if (!cli.containsKey(key) && def != null) {
			sources.put(key, FROM_DEFAULT);
		}
		return cli.computeIfAbsent(key, _ -> def);
	}

	public String require(Map<String, String> cli, String key, String desc) {
		String val = cli.get(key);
		if (val == null) {
			throw CliException.usageError("Error: missing required argument %s", desc);
		}
		return val;
	}

	public String resolveUC(Map<String, String> cli, String key, String def) {
		String val = resolve(cli, key, def);
		return upper(val);
	}

	public String requireUC(Map<String, String> cli, String key, String desc) {
		String val = require(cli, key, desc);
		return upper(val);
	}

	public int resolveInt(Map<String, String> cli, String key, int def) {
		String val = resolve(cli, key, null);
		if (val == null || val.isBlank()) {
			cli.put(key, String.valueOf(def));
			sources.put(key, FROM_DEFAULT);
			return def;
		}

		try {
			return Integer.parseInt(val);
		} catch (NumberFormatException e) {
			throw CliException.error("Invalid integer value for '%s': %s", key, val);
		}
	}

	public int requireInt(Map<String, String> cli, String key, String desc) {
		String val = require(cli, key, desc);
		try {
			return Integer.parseInt(val);
		} catch (NumberFormatException e) {
			throw CliException.error("Invalid integer value for '%s': %s", key, val);
		}
	}

	public boolean resolveBool(Map<String, String> cli, String key, boolean def) {
		String val = resolve(cli, key, null);
		if (val == null || val.isBlank()) {
			cli.put(key, String.valueOf(def));
			sources.put(key, FROM_DEFAULT);
			return def;
		}

		return switch (lower(val)) {
		case "true" -> true;
		case "false" -> false;
		default -> {
			throw CliException.error("Invalid boolean value for '%s': %s", key, val);
		}
		};
	}

	public boolean requireBool(Map<String, String> cli, String key, String desc) {
		String val = require(cli, key, desc);
		return switch (lower(val)) {
		case "true" -> true;
		case "false" -> false;
		default -> {
			throw CliException.error("Invalid boolean value for '%s': %s", key, val);
		}
		};
	}

	/**
	 * Optional language code (default {@code def}), validated and lower-cased as in
	 * {@link #requireLang}.
	 */
	public String resolveLang(Map<String, String> cli, String key, String desc, String def) {
		return checkLang(cli, key, desc, resolve(cli, key, def));
	}

	/**
	 * Required language code: a two-letter ISO 639-1 code in any case. Returns it
	 * in lower case (also stored back in {@code cli}); logs an error and exits with
	 * code 1 when it is missing or invalid.
	 */
	public String requireLang(Map<String, String> cli, String key, String desc) {
		return checkLang(cli, key, desc, require(cli, key, desc));
	}

	private String checkLang(Map<String, String> cli, String key, String desc, String val) {
		if (!LangHelper.validIso2(val)) {
			throw CliException.error(
					"Error: invalid language code '%s' for %s: expected a two-letter ISO 639-1 code (e.g. pt, en)", val,
					desc);
		}
		String code = LangHelper.normalize(val);
		cli.put(key, code);
		return code;
	}

	/**
	 * Optional option, converted by {@code parser}: the value of {@code key} in
	 * {@code cli}, passed to {@code parser}. When it is missing or blank, returns
	 * {@code def}, stored back in {@code cli} as {@code String.valueOf(def)} and
	 * listed as a default by {@link #listProperties}.<br>
	 * {@code parser} reports an invalid value itself, by throwing a
	 * {@link CliException} (e.g. {@code MergeMode::parse}); any other exception it
	 * throws is not caught.
	 */
	public <PA> PA resolveParsed(Function<String, PA> parser, Map<String, String> cli, String key, PA def) {
		String val = resolve(cli, key, null);
		if (val == null || val.isBlank()) {
			cli.put(key, String.valueOf(def));
			sources.put(key, FROM_DEFAULT);
			return def;
		}

		return parser.apply(val);
	}

	/**
	 * Required option, converted by {@code parser}: the value of {@code key} in
	 * {@code cli}, passed to {@code parser}. When it is missing, a usage error
	 * naming {@code desc}, as in {@link #require}.<br>
	 * {@code parser} reports an invalid value itself, by throwing a
	 * {@link CliException} (e.g. {@code MergeMode::parse}); any other exception it
	 * throws is not caught.
	 */
	public <PA> PA requireParsed(Function<String, PA> parser, Map<String, String> cli, String key, String desc) {
		String val = require(cli, key, desc);
		return parser.apply(val);
	}

	void log(String msg, Object... objects) {
		logRaw(ToolLog.prefix(toolName, msg), objects);
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
