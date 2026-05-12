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
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class Action {
	static void main(String[] args) throws Exception {
		Input input = Input.fromEnv();
		ResultWriter resultWriter = ResultWriter.of(input.resultType());
		for (String file : args) {
			Properties properties = input.selectedKeys() == null ? Util.readProperties(file)
			        : Util.selectProperties(Util.readProperties(file), input.selectedKeys(), file);
			resultWriter.write(properties, input);
		}
	}
}


record Input(String[] selectedKeys, String keySeparator, String resultTypeWithArg, String resultType, String resultTypeArg,
        String resultNameSeparator, String outputPrefix) {
	public static Input fromEnv() {
		// In order to keep the defaults DRY (in action.yml), the environment variables are all mandatory.
		String keySeparator = Util.getRequiredEnv("KEY_SEPARATOR");
		// System.getenv("KEYS") == null if set to empty string?! So this cannot be checked to be set if we want to allow empty
		// string:
		String keysStr = System.getenv().getOrDefault("KEYS", "");
		String[] keys = Util.splitArray(keysStr, keySeparator, null);
		String resultNameSeparator = Util.getRequiredEnv("RESULT_NAME_SEPARATOR");

		// RESULT_TYPE format: "<mode>[:<arg>]"
		String resultTypeWithArg = Util.getRequiredEnv("RESULT_TYPE");
		Matcher matcher = Pattern.compile("([^:]+)(?::(.*))?").matcher(resultTypeWithArg);
		if (!matcher.matches()) {
			throw new IllegalArgumentException("invalid resultType: " + resultTypeWithArg);
		}
		String resultType = matcher.group(1);
		String resultTypeArg = Optional.ofNullable(matcher.group(2)).orElse("");
		String outputPrefix = Util.getRequiredEnv("OUTPUT_PREFIX");
		return new Input(keys, keySeparator, resultTypeWithArg, resultType, resultTypeArg, resultNameSeparator, outputPrefix);
	}

	public String requiredResultTypeArg() {
		String arg = resultTypeArg();
		if ("".equals(arg)) {
			throw new IllegalArgumentException("invalid resultType " + resultTypeWithArg() + " (missing argument)");
		}
		return arg;
	}

	public void requireNoArg() {
		if (!"".equals(resultTypeArg())) {
			throw new IllegalArgumentException("invalid resultType " + resultTypeWithArg() + " (non-empty argument)");
		}
	}
}


enum ResultWriter {
	OUTPUT("output") {
		@Override
		public void write(Properties props, Input input) throws IOException {
			try (GitHubVariableWriter writer = GitHubOutputFile.OUTPUT.open()) {
				for (Map.Entry<String, String> entry : Util.stringEntries(props)) {
					writer.write(input.outputPrefix() + entry.getKey(), entry.getValue());
				}

				if (props.size() == 1) {
					String value = Util.stringEntries(props).iterator().next().getValue();
					writer.write("value", value);
				}
			}
		}
	},
	OUTPUT_NAMED("output-named") {
		@Override
		public void write(Properties props, Input input) throws IOException {
			writeNamedImpl(props, input, GitHubOutputFile.OUTPUT);
		}
	},
	ENV_NAMED("env-named") {
		@Override
		public void write(Properties props, Input input) throws IOException {
			writeNamedImpl(props, input, GitHubOutputFile.ENV);
		}
	},
	ENV("env") {
		@Override
		public void write(Properties props, Input input) throws IOException {
			String prefix = input.resultTypeArg();
			try (GitHubVariableWriter writer = GitHubOutputFile.ENV.open()) {
				for (Map.Entry<String, String> entry : Util.stringEntries(props)) {
					writer.write(prefix + entry.getKey(), entry.getValue());
				}
			}
		}
	},
	JSON("json") {
		@Override
		public void write(Properties props, Input input) throws IOException {
			input.requireNoArg();
			try (GitHubVariableWriter writer = GitHubOutputFile.OUTPUT.open()) {
				writer.write("json", Util.toJson(props));
			}
		}
	},
	JSON_FILE("json-file") {
		@Override
		public void write(Properties props, Input input) throws IOException {
			String outputFile = input.requiredResultTypeArg();
			Files.createDirectories((Paths.get(outputFile).getParent()));
			try (Writer writer = Util.openFile(outputFile, StandardOpenOption.CREATE)) {
				writer.write(Util.toJson(props));
				writer.write('\n');
				writer.flush();
			}
		}
	};

	private final String inputName;

	private ResultWriter(String inputName) {
		this.inputName = inputName;
	}

	public static ResultWriter of(String name) {
		for (ResultWriter rw : ResultWriter.values()) {
			if (rw.inputName.equals(name)) {
				return rw;
			}
		}
		throw new IllegalArgumentException("invalid resultType: " + name);
	}

	public abstract void write(Properties props, Input input) throws IOException;

	private static void writeNamedImpl(Properties props, Input input, GitHubOutputFile gitHubOutputFile) throws IOException {
		String[] selectedKeys = input.selectedKeys();
		String[] resultNames = Util.splitArray(input.requiredResultTypeArg(), input.resultNameSeparator(), null);
		if (resultNames.length != selectedKeys.length && resultNames.length != 1) {
			throw new IllegalArgumentException("resultType " + input.resultTypeWithArg() + " has " + resultNames.length
			        + " arguments, but " + selectedKeys.length + " keys are selected");
		}
		try (GitHubVariableWriter writer = gitHubOutputFile.open()) {
			for (int i = 0; i < selectedKeys.length; i++) {
				String varName = resultNames[resultNames.length == 1 ? 0 : i];
				String value = props.getProperty(selectedKeys[i]);
				writer.write(varName, value);
			}
		}
	}
}


enum GitHubOutputFile {
	OUTPUT("GITHUB_OUTPUT", v -> encodeOutputValue(v)), //
	ENV("GITHUB_ENV");

	private final String fileName;
	private final Function<String, String> keyReplacer;

	private GitHubOutputFile(String fileNameEnvVar) {
		this(fileNameEnvVar, v -> v);
	}

	private GitHubOutputFile(String fileNameEnvVar, Function<String, String> valueReplacer) {
		this.fileName = Util.getRequiredEnv(fileNameEnvVar);
		this.keyReplacer = valueReplacer;
	}

	public GitHubVariableWriter open() throws IOException {
		return new GitHubVariableWriter(this.toString().replaceFirst("^GITHUB_", "").toLowerCase(), fileName, keyReplacer);
	}

	private static String encodeOutputValue(String value) {
		StringBuilder result = new StringBuilder(value.length() + 4);
		Matcher matcher = Pattern.compile("([\\p{Punct}&&[^_]])").matcher(value);
		while (matcher.find()) {
			matcher.appendReplacement(result, String.format("-%04X", (int) matcher.group(1).charAt(0)));
		}
		matcher.appendTail(result);
		return result.toString();
	}
}


class GitHubVariableWriter implements AutoCloseable {
	private static final Pattern SIMPLE_VALUE = Pattern.compile("[\\w.-]+");

	private final String description;
	private final Function<String, String> keyReplacer;
	private final Writer writer;

	public GitHubVariableWriter(String description, String fileName, Function<String, String> valueReplacer) throws IOException {
		this.description = description;
		this.writer = Util.openFile(fileName, StandardOpenOption.APPEND);
		this.keyReplacer = Objects.requireNonNull(valueReplacer);
	}

	public void write(String key, String value) throws IOException {
		String encodedKey = keyReplacer.apply(key);
		System.err.format("%s %s\t:= \"%s\"\n", description, encodedKey, value);

		// write (very) simple values in format "<key>=<value>":
		if (SIMPLE_VALUE.matcher(value).matches()) {
			this.writer.write(encodedKey);
			this.writer.write('=');
			this.writer.write(value);
			this.writer.write('\n');
			return;
		}

		// determine separator line which does not occur in value:
		Set<String> valueLines = Set.of(value.split("(?s)\n"));
		String separatorPart = "----";
		String separator = separatorPart;
		while (valueLines.contains(separator)) {
			separator = separator + separatorPart;
		}

		// write in multiline format
		// [https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-commands#multiline-strings]:
		this.writer.write(encodedKey);
		this.writer.write("<<");
		this.writer.write(separator);
		this.writer.write('\n');
		this.writer.write(value);
		this.writer.write('\n');
		this.writer.write(separator);
		this.writer.write('\n');
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
			throw new IllegalArgumentException("missing or empty environment variable " + varName);
		}
		return value;
	}

	public static String[] splitArray(String arrayStr, String separator, String[] defaultResults) {
		return arrayStr.isEmpty() ? defaultResults : arrayStr.split(Pattern.quote(separator), -1);
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

	public static Properties selectProperties(Properties allProps, String[] selectedKeys, String file) {
		Set<String> selectedKeysSet = new LinkedHashSet<>(List.of(selectedKeys));
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
			if (c == '"') {
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
