# read-java-properties

GitHub Action to read a Java .properties file and convert one, multiple, or all properties to plain strings or JSON.

The result can be GitHub outputs, environment variables, or a file, depending on input `resultType`.

Missing property keys are not considered an error, but result in an empty output, unset environment variable, or a JSON
object where the corresponding key is mapped to value `null`, depending on input `resultType`.
The behavior on missing property file is controlled by input `onMissingFile`.

## Inputs and outputs

### Inputs
- `file` (required): the properties file name
- `onMissingFile` (default: "error"): What to do if `file` does not exist or is not readable. One of:
  - "debug-message": write a debug message and continue with an empty properties map,
  - "notice-message": write a notice message and continue with an empty properties map,
  - "warning-message": write a warning message and continue with an empty properties map,
  - "error": write an error message and exit with non-zero status.

  Note that these situations are always treated as an error:
  - `file` is a directory,
  - `file` is readable, but not in the correct format.
- `keys` (default: all): Selects the property keys (names). Format: `keySeparator`-separated list.
  If not specified or empty, all properties are returned.
- `keySeparator` (default: " "): The separator string used in `keys`
- `resultNameSeparator` (default: " "): The separator string used in some types of `resultType`
- `resultType` (default: "output"): The result format and target. One of:
  - "output": For each¹ property key <i>k</i>, set an output named \_<i>k</i>, but with special characters encoded. (The leading underscore serves to avoid conflicts with future output names used by this action.)
  Encoding replaces all punctuation or whitespace characters except the underscore ("_") with "-" followed by four hex digits
  of its unicode code point. For example, for a property key "a.b-c_d", `resultType` "output" sets an output with name
  "_a-002Eb-002Dc_d".
  Additionally, set output "value" to the last found value, unless `keys` is empty. I.e. last given key wins. If `keys` is given, but none is found, "value" is set to empty. (Without keys, this output is not set, because it would be arbitrary due to the "random" iteration order of the used Java class java.util.Properties.)
  - "output-named:<i>names</i>": <i>names</i> is a `resultNameSeparator`-separated list of output names of the same length as (the `keySeparator`-separated list) `keys`. The value for `keys`[i] is set as output <i>names</i>[i]. In order not to hide any future builtin outputs of this action, it is recommended to prefix each name with an underscore ("_"). In this mode, `keys` is required. Unlike "output", names are taken as-is. (This is because no official output naming rules seem to exist yet; so maybe a future user will know better how to choose valid characters than we would implement now.) The output for a missing property is set to empty. If you need to distinguish between empty and undefined properties, `resultType` "json" or "json-file" is recommended.
  - "output-named:<i>name</i>": Special case: A single name is supported even with multiple `keys`. All found values for the keys are set as the same output; the last found key wins. In this mode, `keys` is required. If none is found, the output is set to empty.
  - "env": For each¹ property key <i>k</i>, set an environment variable <i>k</i>.
  In order not to pollute the environment with hard to understand variables, this should only be used to set some specifically named variables. I.e. you know that the property file can only contain such properties or you are selecting only such properties with `keys`.
  - "env:<i>prefix</i>": For each¹ property key <i>K</i>, set an environment variable <i>prefix</i><i>K</i>.
  - "env-named:<i>names</i>": <i>names</i> is a `resultNameSeparator`-separated list of variable names of the same length as (the `keySeparator`-separated list) `keys`. The value for `keys`[i] is set as environment variable <i>names</i>[i]. In this mode, `keys` is required. The environment variable for a missing property is not set.
  - "env-named:<i>name</i>": Special case: A single name is supported even with multiple `keys`. All found values for the keys are set as the same environment variable; the last found key wins. In this mode, `keys` is required.
  - "json": Set an action output "json" to all¹ properties as a JSON object, formatted as a single-line string. Selected keys for missing properties are included with value null.
  - "json-file:<i>name</i>": Write a file with name <i>name</i> with contents: all¹ properties as a JSON object (formatting not specified). Selected keys for missing properties are included with value null.

  ¹Here, "each property" etc. means each property selected by input `keys` if it is non-empty.

### Outputs
- `value`: The (plain) property value. Only set in certain modes, see input `resultType`.
- `json`: All found properties matching the `keys` input as a single-line JSON object. Only set in certain modes, see input `resultType`.


## Usage examples:

Suppose file gradle.properties contains properties `sourceJavaVersion= 21`, `targetJavaVersion= 17`, and `org.gradle.jvmargs= -ea -showversion`.

Query all properties as action outputs:
```
- id: readProp
  uses: freenet-actions/read-java-properties@v1
  with:
    file: gradle.properties
```
⇒ \
  `${{steps.readProp.outputs._sourceJavaVersion}}` == `21`, \
  `${{steps.readProp.outputs._targetJavaVersion}}` == `17`, \
  `${{steps.readProp.outputs._org-002Egradle-002Ejvmargs}}` == `-ea -showversion`.

Query multiple properties as action outputs:
```
- id: readProp
  uses: freenet-actions/read-java-properties@v1
  with:
    file: gradle.properties
    keys: 'sourceJavaVersion targetJavaVersion'
```
or with another key separator:
```
- id: readProp
  uses: freenet-actions/read-java-properties@v1
  with:
    file: gradle.properties
    keys: sourceJavaVersion,targetJavaVersion
    keySeparator: ,
```
⇒ \
  `${{steps.readProp.outputs._sourceJavaVersion}}` == `21`, \
  `${{steps.readProp.outputs._targetJavaVersion}}` == `17`, \
  `${{steps.readProp.outputs.value}}` == `17`.

Query multiple properties as action output of which only one is found:
```
- id: readProp
  uses: freenet-actions/read-java-properties@v1
  with:
    file: gradle.properties
    keys: 'foo targetJavaVersion bar'
```
⇒ \
  `${{steps.readProp.outputs._targetJavaVersion}}` == `17`, \
  `${{steps.readProp.outputs.value}}` == `17`.

Query a single property as action output:
```
- id: readProp
  uses: freenet-actions/read-java-properties@v1
  with:
    file: gradle.properties
    keys: org.gradle.jvmargs
```
⇒ \
  `${{steps.readProp.outputs._org-002Egradle-002Ejvmargs}}` == `-ea -showversion`, \
  `${{steps.readProp.outputs.value}}` == `-ea -showversion`.

Query multiple (alternative) properties as a single action output. In other words, query a property with a fallback to another property.
```
- uses: freenet-actions/read-java-properties@v1
  with:
    file: gradle.properties
    keys: sourceJavaVersion,targetJavaVersion
    resultType: 'output-named:_javaVersion'
```
⇒ `${{steps.readProp.outputs._javaVersion}}` == `17`.

Query all properties as environment variables of the same names:
```
- uses: freenet-actions/read-java-properties@v1
  with:
    file: gradle.properties
    resultType: env
```
⇒ Environment variables \
  `sourceJavaVersion` == `21`, \
  `targetJavaVersion` == `17`, \
  `org.gradle.jvmargs` == `-ea -showversion`.

Query all properties as environment variables with prefix:
```
- uses: freenet-actions/read-java-properties@v1
  with:
    file: gradle.properties
    resultType: "env:GRADLE_PROP_"
```
⇒ Environment variables \
  `GRADLE_PROP_sourceJavaVersion` == `21`, \
  `GRADLE_PROP_targetJavaVersion` == `17`, \
  `GRADLE_PROP_org.gradle.jvmargs` == `-ea -showversion`.

Query multiple properties as environment variables:
```
- uses: freenet-actions/read-java-properties@v1
  with:
    file: gradle.properties
    keys: sourceJavaVersion,targetJavaVersion
    resultType: env
```
⇒ Environment variables \
  `sourceJavaVersion` == `21`, \
  `targetJavaVersion` == `17`.

Query multiple properties as environment variables of given names:
```
- uses: freenet-actions/read-java-properties@v1
  with:
    file: gradle.properties
    keys: sourceJavaVersion,targetJavaVersion
    resultType: env-named:SOURCE_JAVA_VERSION,TARGET_JAVA_VERSION
```
⇒ Environment variables \
  `SOURCE_JAVA_VERSION` == `21`, \
  `TARGET_JAVA_VERSION` == `17`.

Query multiple (alternative) properties as a single environment variable. In other words, query a property with a fallback to another property.
```
- uses: freenet-actions/read-java-properties@v1
  with:
    file: gradle.properties
    keys: sourceJavaVersion,targetJavaVersion
    resultType: "env-named:JAVA_VERSION"
```
⇒ Environment variable `JAVA_VERSION` == `17`.

Query all properties as JSON action output:
```
- id: readProp
  uses: freenet-actions/read-java-properties@v1
  with:
    file: gradle.properties
    resultType: json
```
⇒ `${{steps.readProp.outputs.json}}` == `{"sourceJavaVersion": "21", "targetJavaVersion": "17", "org.gradle.jvmargs": "-ea -showversion"}` \
(or similar; order not guaranteed).

Query all properties as JSON file:
```
- id: readProp
  uses: freenet-actions/read-java-properties@v1
  with:
    file: gradle.properties
    resultType: json-file:/tmp/gradle-properties.json
```
⇒ File /tmp/gradle-properties.json contains \
  `{"sourceJavaVersion": "21", "targetJavaVersion": "17", "org.gradle.jvmargs": "-ea -showversion"}` \
(or similar; order and formatting not guaranteed).
