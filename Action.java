import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.AbstractMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class Action {
	private Action() {}

	static void main(String[] args) throws Exception {
		try {
			Config config = Config.fromEnv();
			ResultWriter resultWriter = ResultWriter.of(config.resultType());
			for (String file : args) {
				Properties properties = config.selectedKeys() == null ? Util.readProperties(file)
				        : Util.selectProperties(Util.readProperties(file), config.selectedKeys(), file);
				resultWriter.write(properties, config);
			}
		} catch (OutputException e) {
			try (GitHubVariableWriter writer = GitHubOutputFile.OUTPUT.open()) {
				// This output is not documented
				writer.write("error", e.getMessage());
			}
			throw e;
		}
	}
}


/**
 * An exception for which an "error" output should be set.
 */
class OutputException extends RuntimeException {
	public OutputException(String message, Throwable cause) {
		super(message, cause);
	}

	public static OutputException forIllegalArgument(String message) {
		return new OutputException(message, new IllegalArgumentException(message));
	}
}


/**
 * Configuration environment variable names and their corresponding action input names for error messages.
 */
enum ConfigVariable {
	FILE("file"), //
	KEYS("keys"), //
	RESULT_TYPE("resultType"), //
	KEY_SEPARATOR("keySeparator"), //
	RESULT_NAME_SEPARATOR("resultNameSeparator"), //
	OUTPUT_PREFIX(null);

	public final String inputName;

	private ConfigVariable(String inputName) {
		this.inputName = inputName;
	}

	@Override
	public String toString() {
		return inputName;
	}
}


record Config(List<String> selectedKeys, String keySeparator, String resultTypeWithArg, String resultType, String resultTypeArg,
        String resultNameSeparator, String outputPrefix) {
	public static Config fromEnv() {
		// In order to keep the defaults DRY (in action.yml), the environment variables are all mandatory.
		String keySeparator = Util.getRequiredEnv(ConfigVariable.KEY_SEPARATOR);
		// System.getenv("KEYS") == null if set to empty string?! So this cannot be checked to be set if we want to allow empty
		// string:
		String keysStr = System.getenv().getOrDefault(ConfigVariable.KEYS.name(), "");
		Optional<String[]> keys = Util.splitArray(keysStr, keySeparator);
		String resultNameSeparator = Util.getRequiredEnv(ConfigVariable.RESULT_NAME_SEPARATOR);

		// RESULT_TYPE format: "<mode>[:<arg>]"
		String resultTypeWithArg = Util.getRequiredEnv(ConfigVariable.RESULT_TYPE);
		Matcher matcher = Pattern.compile("([^:]+)(?::(.*))?").matcher(resultTypeWithArg);
		if (!matcher.matches()) {
			throw OutputException.forIllegalArgument("invalid " + ConfigVariable.RESULT_TYPE + ": " + resultTypeWithArg);
		}
		String resultType = matcher.group(1);
		String resultTypeArg = Optional.ofNullable(matcher.group(2)).orElse("");
		String outputPrefix = Util.getRequiredEnv(ConfigVariable.OUTPUT_PREFIX);
		return new Config(keys.map(List::of).orElse(null), keySeparator, resultTypeWithArg, resultType, resultTypeArg,
		        resultNameSeparator, outputPrefix);
	}

	public String requiredResultTypeArg() {
		String arg = resultTypeArg();
		if ("".equals(arg)) {
			throw OutputException.forIllegalArgument(
			        "invalid " + ConfigVariable.RESULT_TYPE + " " + resultTypeWithArg() + " (missing argument)");
		}
		return arg;
	}

	public void requireNoArg() {
		if (!"".equals(resultTypeArg())) {
			throw OutputException.forIllegalArgument(
			        "invalid " + ConfigVariable.RESULT_TYPE + " " + resultTypeWithArg() + " (non-empty argument)");
		}
	}
}


enum ResultWriter {
	OUTPUT("output") {
		@Override
		public void write(Properties props, Config config) throws IOException {
			String lastValue = null;
			try (GitHubVariableWriter writer = GitHubOutputFile.OUTPUT.open()) {
				for (Map.Entry<String, String> entry : Util.stringEntries(props)) {
					String key = encodeKey(config, config.outputPrefix() + entry.getKey());
					lastValue = entry.getValue();
					writer.write(key, lastValue);
				}

				// TODO props must be in order of config.selectedKeys()
				// Otherwise, this is arbitrary:
				if (lastValue != null) {
					writer.write("value", lastValue);
				}
			}
		}

		private static String encodeKey(Config config, String key) {
			StringBuilder result = new StringBuilder(key.length() + config.outputPrefix().length() + 4);
			Matcher matcher = Pattern.compile("([\\p{Punct}&&[^_]])").matcher(key);
			while (matcher.find()) {
				matcher.appendReplacement(result, String.format("-%04X", (int) matcher.group(1).charAt(0)));
			}
			matcher.appendTail(result);
			return result.toString();
		}
	},
	OUTPUT_NAMED("output-named") {
		@Override
		public void write(Properties props, Config config) throws IOException {
			writeNamedImpl(props, config, GitHubOutputFile.OUTPUT);
		}
	},
	ENV_NAMED("env-named") {
		@Override
		public void write(Properties props, Config config) throws IOException {
			writeNamedImpl(props, config, GitHubOutputFile.ENV);
		}
	},
	ENV("env") {
		@Override
		public void write(Properties props, Config config) throws IOException {
			String prefix = config.resultTypeArg();
			try (GitHubVariableWriter writer = GitHubOutputFile.ENV.open()) {
				for (Map.Entry<String, String> entry : Util.stringEntries(props)) {
					writer.write(prefix + entry.getKey(), entry.getValue());
				}
			}
		}
	},
	JSON("json") {
		@Override
		public void write(Properties props, Config config) throws IOException {
			config.requireNoArg();
			try (GitHubVariableWriter writer = GitHubOutputFile.OUTPUT.open()) {
				writer.write("json", Util.toJson(props));
			}
		}
	},
	JSON_FILE("json-file") {
		@Override
		public void write(Properties props, Config config) throws IOException {
			String outputFile = config.requiredResultTypeArg();
			Files.createDirectories((Paths.get(outputFile).getParent()));
			try (Writer writer = Util.openFile(outputFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
				String jsonResult = Util.toJson(props);
				System.err.format("writing to %s: %s%n", outputFile, jsonResult);
				writer.write(jsonResult);
				writer.write('\n');
				writer.flush();
			}
		}
	};

	private final String inputName;

	private ResultWriter(String inputName) {
		this.inputName = inputName;
	}

	public static ResultWriter of(String inputName) {
		for (ResultWriter rw : ResultWriter.values()) {
			if (rw.inputName.equals(inputName)) {
				return rw;
			}
		}
		throw OutputException.forIllegalArgument("invalid " + ConfigVariable.RESULT_TYPE + ": " + inputName);
	}

	public abstract void write(Properties props, Config config) throws IOException;

	private static void writeNamedImpl(Properties props, Config config, GitHubOutputFile gitHubOutputFile) throws IOException {
		List<String> selectedKeys = config.selectedKeys();
		if (selectedKeys == null) {
			throw OutputException.forIllegalArgument(
			        "invalid use of " + ConfigVariable.RESULT_TYPE + " " + config.resultType() + " (missing keys)");
		}
		@SuppressWarnings("java:S3655") // Sonar rule: "Optional value should only be accessed after calling isPresent()".
		// requiredResultTypeArg() is not empty, so splitArray returns non-empty
		String[] resultNames = Util.splitArray(config.requiredResultTypeArg(), config.resultNameSeparator()).get();
		if (resultNames.length != selectedKeys.size() && resultNames.length != 1) {
			throw OutputException.forIllegalArgument(ConfigVariable.RESULT_TYPE + " " + config.resultTypeWithArg() + " has "
			        + resultNames.length + " arguments, but " + selectedKeys.size() + " keys are selected");
		}
		try (GitHubVariableWriter writer = gitHubOutputFile.open()) {
			for (int i = 0; i < selectedKeys.size(); i++) {
				String name = resultNames[resultNames.length == 1 ? 0 : i];
				String value = props.getProperty(selectedKeys.get(i));
				writer.write(name, value);
			}
		}
	}
}


enum GitHubOutputFile {
	OUTPUT("GITHUB_OUTPUT"), //
	ENV("GITHUB_ENV");

	final String fileName;

	private GitHubOutputFile(String fileNameEnvVar) {
		this.fileName = Util.getRequiredEnv(fileNameEnvVar);
	}

	public GitHubVariableWriter open() throws IOException {
		return new GitHubVariableWriter(this.toString().replaceFirst("^GITHUB_", "").toLowerCase(), fileName);
	}
}


class GitHubVariableWriter implements AutoCloseable {
	private static final Pattern SIMPLE_VALUE = Pattern.compile("[\\w.-]+");

	private final String description;
	private final Writer writer;

	public GitHubVariableWriter(String description, String fileName) throws IOException {
		this.description = description;
		this.writer = Util.openFile(fileName, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}

	public void write(String key, String value) throws IOException {
		System.err.format("%s %s\t:= \"%s\"%n", description, key, value);

		// write (very) simple values in format "<key>=<value>":
		if (SIMPLE_VALUE.matcher(value).matches()) {
			this.writer.write(key);
			this.writer.write('=');
			this.writer.write(value);
			this.writer.write('\n');
		} else {
			writeMultiLine(key, value);
		}
	}

	/**
	 * Writes a key-value pair in
	 * <a href="https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-commands#multiline-strings">multiline
	 * format</a>.
	 */
	private void writeMultiLine(String key, String value) throws IOException {
		String separator = computeSeparator(value);

		this.writer.write(key);
		this.writer.write("<<");
		this.writer.write(separator);
		this.writer.write('\n');
		this.writer.write(value);
		this.writer.write('\n');
		this.writer.write(separator);
		this.writer.write('\n');
	}

	/**
	 * Computes a separator line which does not occur in value.
	 */
	@SuppressWarnings("java:S1643") // Sonar rule: "Strings should not be concatenated using '+' in a loop".
	// False positive: We would need that StringBuilder's toString for each iteration for the contains check anyway.
	private static String computeSeparator(String value) {
		Set<String> valueLines = Set.of(value.split("(?s)\n"));
		String separatorPart = "----";
		@SuppressWarnings("java:S1643")
		String separator = separatorPart;
		while (valueLines.contains(separator)) {
			separator = separator + separatorPart;
		}
		return separator;
	}

	@Override
	public void close() throws IOException {
		this.writer.close();
	}
}


class Util {
	private Util() {}

	public static String getRequiredEnv(String varName) {
		String value = System.getenv(varName);
		if (value == null || value.isEmpty()) {
			throw OutputException.forIllegalArgument("missing or empty environment variable " + varName);
		}
		return value;
	}

	public static String getRequiredEnv(ConfigVariable varName) {
		return getRequiredEnv(varName.name());
	}

	public static Optional<String[]> splitArray(String arrayStr, String separator) {
		return arrayStr.isEmpty() ? Optional.empty() : Optional.of(arrayStr.split(Pattern.quote(separator), -1));
	}

	public static Writer openFile(String name, OpenOption... options) throws IOException {
		return new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(Paths.get(name), options), StandardCharsets.UTF_8));
	}

	public static Properties readProperties(String file) throws IOException {
		Properties allProps = new Properties();
		try (InputStream in = Files.newInputStream(Paths.get(file))) {
			allProps.load(in);
		}
		return allProps;
	}

	public static Properties selectProperties(Properties allProps, List<String> selectedKeys, String file) {
		Set<String> selectedKeysSet = new LinkedHashSet<>(selectedKeys);
		Properties props = new Properties();
		for (Map.Entry<String, String> entry : stringEntries(allProps)) {
			if (selectedKeysSet.contains(entry.getKey())) {
				selectedKeysSet.remove(entry.getKey());
				props.setProperty(entry.getKey(), entry.getValue());
			}
		}
		for (String key : selectedKeysSet) {
			System.err.format("Property %s not found in %s%n", key, file);
		}
		return props;
	}

	public static Iterable<Map.Entry<String, String>> stringEntries(Properties props) {
		return () -> new Iterator<>() {
			private final Iterator<String> keysIter = props.stringPropertyNames().iterator();

			@Override
			public Map.Entry<String, String> next() {
				String key = keysIter.next();
				return new AbstractMap.SimpleImmutableEntry<>(key, props.getProperty(key));
			}

			@Override
			public boolean hasNext() {
				return keysIter.hasNext();
			}
		};
	}

	public static String toJson(Properties props) {
		StringBuilder s = new StringBuilder(50).append('{');
		int initialLength = s.length();
		for (Map.Entry<String, String> entry : stringEntries(props)) {
			s.append(s.length() == initialLength ? '"' : ", \"");
			appendJsonString(s, entry.getKey());
			s.append("\":\"");
			appendJsonString(s, entry.getValue());
			s.append('"');
		}
		return s.append('}').toString();
	}

	private static void appendJsonString(StringBuilder buffer, String s) {
		for (char c : s.toCharArray()) {
			if (c == ' ') {
				buffer.append(c);
			} else if (c == '"') {
				buffer.append('\\').append('"');
			} else if (c == '\t') {
				buffer.append('\\').append('t');
			} else if (c == '\n') {
				buffer.append('\\').append('n');
			} else if (c == '\r') {
				buffer.append('\\').append('r');
			} else if (c == '\f') {
				buffer.append('\\').append('f');
			} else if (c == '\b') {
				buffer.append('\\').append('b');
			} else if (c < ' ' || Character.isWhitespace(c)) {
				buffer.append('\\').append('u').append(String.format("%04X", (int) c));
			} else {
				buffer.append(c);
			}
		}
	}
}
