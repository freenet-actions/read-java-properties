import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class Action {
	private Action() {}

	public static void main(String[] args) throws Exception {
		try {
			Config config = Config.fromEnv();
			ResultWriter resultWriter = ResultWriter.of(config.resultType());
			for (String file : args) {
				Properties properties = Util.readProperties(file);
				// If selectedKeys is set, this Map will have exactly those keys and values may be empty (where selected key is
				// not found in properties).
				// Otherwise, this Map will have the same keys as the properties file, and "not found" is not possible.
				Map<String, Optional<String>> selectedProperties = config.selectedKeys()
				        .map(keys -> Util.selectProperties(properties, keys, file)) //
				        .orElseGet(() -> Util.stringEntries(properties));
				resultWriter.write(selectedProperties, config);
			}
		} catch (IoRuntimeException e) {
			throw e.getCause();
		} catch (OutputException e) {
			try (GitHubVariableWriter writer = GitHubOutputFile.OUTPUT.open()) {
				writer.write(Ids.OutputName.ERROR, e.getMessage());
			}
			throw e;
		}
	}
}


/**
 * Identifiers which must match those specified in the action YAML.
 */
class Ids {
	private Ids() {}

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

		public final String externalName;

		private ConfigVariable(String externalName) {
			this.externalName = externalName;
		}

		@Override
		public String toString() {
			return externalName;
		}
	}

	enum ResultWriterName {
		OUTPUT("output"), //
		OUTPUT_NAMED("output-named"), //
		ENV_NAMED("env-named"), //
		ENV("env"), //
		JSON("json"), //
		JSON_FILE("json-file");

		public final String externalName;

		private ResultWriterName(String externalName) {
			this.externalName = externalName;
		}
	}

	enum OutputName {
		JSON, //
		VALUE, //
		ERROR; // This output is not documented.
		public final String externalName = name().toLowerCase();
	}
}


class IoRuntimeException extends RuntimeException {
	public IoRuntimeException(IOException cause) {
		super(cause);
	}

	@Override
	public synchronized IOException getCause() {
		return (IOException) super.getCause();
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


record Config(Optional<List<String>> selectedKeys, String keySeparator, String resultTypeWithArg, String resultType,
        String resultTypeArg, String resultNameSeparator, String outputPrefix) {
	public static Config fromEnv() {
		// In order to keep the defaults DRY (in action.yml), the environment variables are all mandatory.
		String keySeparator = Util.getRequiredEnv(Ids.ConfigVariable.KEY_SEPARATOR);
		// System.getenv("KEYS") == null if set to empty string?! So this cannot be checked to be set if we want to allow empty
		// string:
		String keysStr = System.getenv().getOrDefault(Ids.ConfigVariable.KEYS.name(), "");
		Optional<String[]> keys = Util.splitArray(keysStr, keySeparator);
		String resultNameSeparator = Util.getRequiredEnv(Ids.ConfigVariable.RESULT_NAME_SEPARATOR);

		// RESULT_TYPE format: "<mode>[:<arg>]"
		String resultTypeWithArg = Util.getRequiredEnv(Ids.ConfigVariable.RESULT_TYPE);
		Matcher matcher = Pattern.compile("([^:]+)(?::(.*))?").matcher(resultTypeWithArg);
		if (!matcher.matches()) {
			throw OutputException.forIllegalArgument("invalid " + Ids.ConfigVariable.RESULT_TYPE + ": " + resultTypeWithArg);
		}
		String resultType = matcher.group(1);
		String resultTypeArg = Optional.ofNullable(matcher.group(2)).orElse("");
		String outputPrefix = Util.getRequiredEnv(Ids.ConfigVariable.OUTPUT_PREFIX);
		return new Config(keys.map(List::of), keySeparator, resultTypeWithArg, resultType, resultTypeArg, resultNameSeparator,
		        outputPrefix);
	}

	public String requiredResultTypeArg() {
		String arg = resultTypeArg();
		if ("".equals(arg)) {
			throw OutputException.forIllegalArgument(
			        "invalid " + Ids.ConfigVariable.RESULT_TYPE + " " + resultTypeWithArg() + " (missing argument)");
		}
		return arg;
	}

	public void requireNoArg() {
		if (!"".equals(resultTypeArg())) {
			throw OutputException.forIllegalArgument(
			        "invalid " + Ids.ConfigVariable.RESULT_TYPE + " " + resultTypeWithArg() + " (non-empty argument)");
		}
	}
}


enum ResultWriter {
	OUTPUT(Ids.ResultWriterName.OUTPUT) {
		@Override
		public void write(Map<String, Optional<String>> props, Config config) throws IOException {
			String lastValue = null;
			try (GitHubVariableWriter writer = GitHubOutputFile.OUTPUT.open()) {
				for (Map.Entry<String, Optional<String>> entry : props.entrySet()) {
					String key = encodeKey(config.outputPrefix() + entry.getKey());
					lastValue = entry.getValue().orElse("");
					writer.write(key, lastValue);
				}

				if (config.selectedKeys().isPresent()) {
					writer.write(Ids.OutputName.VALUE, lastValue != null ? lastValue : "");
				}
			}
		}

		private static String encodeKey(String key) {
			StringBuilder result = new StringBuilder(key.length() + 4);
			Matcher matcher = Pattern.compile("([\\s\\p{Punct}&&[^_]])").matcher(key);
			while (matcher.find()) {
				matcher.appendReplacement(result, String.format("-%04X", (int) matcher.group(1).charAt(0)));
			}
			matcher.appendTail(result);
			return result.toString();
		}
	},
	OUTPUT_NAMED(Ids.ResultWriterName.OUTPUT_NAMED) {
		@Override
		public void write(Map<String, Optional<String>> props, Config config) throws IOException {
			writeNamedImpl(props, config, true, GitHubOutputFile.OUTPUT);
		}
	},
	ENV_NAMED(Ids.ResultWriterName.ENV_NAMED) {
		@Override
		public void write(Map<String, Optional<String>> props, Config config) throws IOException {
			writeNamedImpl(props, config, false, GitHubOutputFile.ENV);
		}
	},
	ENV(Ids.ResultWriterName.ENV) {
		@Override
		public void write(Map<String, Optional<String>> props, Config config) throws IOException {
			String prefix = config.resultTypeArg();
			try (GitHubVariableWriter writer = GitHubOutputFile.ENV.open()) {
				for (Map.Entry<String, Optional<String>> entry : props.entrySet()) {
					Optional<String> value = entry.getValue();
					value.ifPresent(v -> writer.write(prefix + entry.getKey(), v));
				}
			}
		}
	},
	JSON(Ids.ResultWriterName.JSON) {
		@Override
		public void write(Map<String, Optional<String>> props, Config config) throws IOException {
			config.requireNoArg();
			try (GitHubVariableWriter writer = GitHubOutputFile.OUTPUT.open()) {
				writer.write(Ids.OutputName.JSON, Util.toJson(props).s());
			}
		}
	},
	JSON_FILE(Ids.ResultWriterName.JSON_FILE) {
		@Override
		public void write(Map<String, Optional<String>> props, Config config) throws IOException {
			String outputFile = config.requiredResultTypeArg();
			Path parentDir = Paths.get(outputFile).getParent();
			if (parentDir != null) {
				Files.createDirectories(parentDir);
			}
			try (Writer writer = Util.openFile(outputFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
				StringIntPair jsonResult = Util.toJson(props);
				System.err.format("writing JSON for %s properties to %s%n", jsonResult.i(), outputFile);
				writer.write(jsonResult.s());
				writer.write('\n');
				writer.flush();
			}
		}
	};

	private final String externalName;

	private ResultWriter(Ids.ResultWriterName externalName) {
		this.externalName = externalName.externalName;
	}

	public static ResultWriter of(String externalName) {
		for (ResultWriter rw : ResultWriter.values()) {
			if (rw.externalName.equals(externalName)) {
				return rw;
			}
		}
		throw OutputException.forIllegalArgument("invalid " + Ids.ConfigVariable.RESULT_TYPE + ": " + externalName);
	}

	public abstract void write(Map<String, Optional<String>> props, Config config) throws IOException;

	private static void writeNamedImpl(Map<String, Optional<String>> props, Config config, boolean includeMissing,
	        GitHubOutputFile gitHubOutputFile) throws IOException {
		List<String> selectedKeys = config.selectedKeys().orElseThrow(() -> OutputException.forIllegalArgument("invalid use of "
		        + Ids.ConfigVariable.RESULT_TYPE + " " + config.resultType() + " (missing " + Ids.ConfigVariable.KEYS + ")"));

		@SuppressWarnings("java:S3655") // Sonar rule: "Optional value should only be accessed after calling isPresent()".
		// requiredResultTypeArg() is not empty, so splitArray returns non-empty
		String[] resultNames = Util.splitArray(config.requiredResultTypeArg(), config.resultNameSeparator()).get();
		if (resultNames.length != selectedKeys.size() && resultNames.length != 1) {
			throw OutputException.forIllegalArgument(Ids.ConfigVariable.RESULT_TYPE + " " + config.resultTypeWithArg() + " has "
			        + resultNames.length + " arguments, but " + selectedKeys.size() + " keys are selected");
		}
		try (GitHubVariableWriter writer = gitHubOutputFile.open()) {
			for (int i = 0; i < selectedKeys.size(); i++) {
				String name = resultNames[resultNames.length == 1 ? 0 : i];
				Optional<String> value = props.get(selectedKeys.get(i));
				value.ifPresentOrElse(v -> writer.write(name, v), () -> {
					if (includeMissing) {
						writer.write(name, "");
					} else {
						// Nothing to do. "not found" has already been logged.
					}
				});
			}
		}
	}
}


enum GitHubOutputFile {
	OUTPUT("GITHUB_OUTPUT", "output"), //
	ENV("GITHUB_ENV", "environment variable");

	private final String fileName;
	private final String description;

	private GitHubOutputFile(String fileNameEnvVar, String description) {
		this.fileName = Util.getRequiredEnv(fileNameEnvVar);
		this.description = description;
	}

	public GitHubVariableWriter open() throws IOException {
		return new GitHubVariableWriter(description, fileName);
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

	public void write(String key, String value) {
		try {
			System.err.format("setting %s %s%n", description, key);

			// write (very) simple values in format "<key>=<value>":
			if (SIMPLE_VALUE.matcher(value).matches()) {
				this.writer.write(key);
				this.writer.write('=');
				this.writer.write(value);
				this.writer.write('\n');
			} else {
				writeMultiLine(key, value);
			}
		} catch (IOException e) {
			throw new IoRuntimeException(e);
		}
	}

	public void write(Ids.OutputName key, String value) {
		write(key.externalName, value);
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

	public static String getRequiredEnv(Ids.ConfigVariable varName) {
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

	/**
	 * Selects the properties with the given keys and returns them as a Map with the corresponding iteration order.
	 */
	public static Map<String, Optional<String>> selectProperties(Properties allProps, List<String> selectedKeys, String file) {
		Set<String> unmatchedKeysSet = new LinkedHashSet<>(selectedKeys);
		Map<String, Optional<String>> results = new LinkedHashMap<>();
		for (String key : selectedKeys) {
			String value = allProps.getProperty(key);
			results.put(key, Optional.ofNullable(value));
			if (value != null) {
				unmatchedKeysSet.remove(key);
			}
		}
		for (String key : unmatchedKeysSet) {
			System.err.format("Property %s not found in %s%n", key, file);
		}
		return results;
	}

	/**
	 * @return the Properties as a Map with each value as a non-empty Optional (strange, but useful for our use case)
	 */
	public static Map<String, Optional<String>> stringEntries(Properties props) {
		Map<String, Optional<String>> map = new LinkedHashMap<>();
		for (String key : props.stringPropertyNames()) {
			map.put(key, Optional.of(props.getProperty(key)));
		}
		return map;
	}

	/**
	 * @return the JSON string and the number of entries with non-empty value
	 */
	public static StringIntPair toJson(Map<String, Optional<String>> map) {
		AtomicInteger size = new AtomicInteger(0);
		StringBuilder s = new StringBuilder(50).append('{');
		int initialLength = s.length();
		for (Map.Entry<String, Optional<String>> entry : map.entrySet()) {
			if (s.length() != initialLength) {
				s.append(", ");
			}
			s.append('"');
			appendJsonString(s, entry.getKey());
			s.append("\": ");
			entry.getValue().ifPresentOrElse(value -> {
				size.incrementAndGet();
				s.append('"');
				appendJsonString(s, value);
				s.append('"');
			}, () -> s.append("null"));
		}
		s.append('}');
		return new StringIntPair(s.toString(), size.get());
	}

	private static void appendJsonString(StringBuilder buffer, String s) {
		for (char c : s.toCharArray()) {
			if (c == ' ') {
				buffer.append(c);
			} else if (c == '\\') {
				buffer.append('\\').append('\\');
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


record StringIntPair(String s, int i) {
}
