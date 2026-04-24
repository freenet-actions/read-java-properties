# read-java-properties

Github Action to read a Java .properties file and output one, multiple, or all properties as plain strings or JSON.

## Usage example:

Suppose file gradle.properties contains properties `sourceJavaVersion= 21`, `targetJavaVersion= 17`, and `org.gradle.jvmargs= -ea -showversion`.

Query all properties as action outputs:
```
- id: readProp
  uses: freenet-actions/read-java-properties@v1
  with:
    file: gradle.properties
```
⇒ `${{steps.readProp.outputs._sourceJavaVersion}}` == `21`, `${{steps.readProp.outputs._targetJavaVersion}}` == `17`, `${{steps.readProp.outputs._org.gradle.jvmargs}}` == `-ea -showversion`.

Query multiple properties as action outputs:
```
- id: readProp
  uses: freenet-actions/read-java-properties@v1
  with:
    file: gradle.properties
    keys: sourceJavaVersion,targetJavaVersion
```
or with another key separator:
```
- id: readProp
  uses: freenet-actions/read-java-properties@v1
  with:
    file: gradle.properties
    keys: "sourceJavaVersion; targetJavaVersion"
    keySeparator: "; "
```
⇒ `${{steps.readProp.outputs._sourceJavaVersion}}` == `21`, `${{steps.readProp.outputs._targetJavaVersion}}` == `17`, `${{steps.readProp.outputs.value}}` == `17`.

Query multiple properties as action output of which only one is found:
```
- id: readProp
  uses: freenet-actions/read-java-properties@v1
  with:
    file: gradle.properties
    keys: foo,targetJavaVersion,bar
```
⇒ `${{steps.readProp.outputs._targetJavaVersion}}` == `17`, `${{steps.readProp.outputs.value}}` == `17`.

Query a single property as action output:
```
- id: readProp
  uses: freenet-actions/read-java-properties@v1
  with:
    file: gradle.properties
    keys: org.gradle.jvmargs
```
⇒ `${{steps.readProp.outputs.org.gradle.jvmargs}}` == `-ea -showversion`, `${{steps.readProp.outputs.value}}` == `-ea -showversion`.

Query all properties as environment variables of the same names:
```
- uses: freenet-actions/read-java-properties@v1
  with:
    file: gradle.properties
    resultType: env
```
⇒ Environment variables `sourceJavaVersion` == `21`, `targetJavaVersion` == `17`, `org.gradle.jvmargs` == `-ea -showversion`.

Query all properties as environment variables with prefix:
```
- uses: freenet-actions/read-java-properties@v1
  with:
    file: gradle.properties
    resultType: "env:GRADLE_PROP_"
```
⇒ Environment variables `GRADLE_PROP_sourceJavaVersion` == `21`, `GRADLE_PROP_targetJavaVersion` == `17`, `GRADLE_PROP_org.gradle.jvmargs` == `-ea -showversion`.

Query multiple properties as environment variables:
```
- uses: freenet-actions/read-java-properties@v1
  with:
    file: gradle.properties
    keys: sourceJavaVersion,targetJavaVersion
    resultType: env
```
⇒ Environment variables `sourceJavaVersion` == `21`, `targetJavaVersion` == `17`.

Query multiple properties as environment variables of given names:
```
- uses: freenet-actions/read-java-properties@v1
  with:
    file: gradle.properties
    keys: sourceJavaVersion,targetJavaVersion
    resultType: env-named:SOURCE_JAVA_VERSION,TARGET_JAVA_VERSION
```
⇒ Environment variables `SOURCE_JAVA_VERSION` == `21`, `TARGET_JAVA_VERSION` == `17`.

Query multiple (alternative) properties as a single environment variable:
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
⇒ `${{steps.readProp.outputs.json}}` == `{"sourceJavaVersion": "21", "targetJavaVersion": "17", "org.gradle.jvmargs": "-ea -showversion"}` (or similar; order not guaranteed).

Query all properties as JSON file:
```
- id: readProp
  uses: freenet-actions/read-java-properties@v1
  with:
    file: gradle.properties
    resultType: json-file:/tmp/gradle-properties.json
```
⇒ File /tmp/gradle-properties.json contains `{"sourceJavaVersion": "21", "targetJavaVersion": "17", "org.gradle.jvmargs": "-ea -showversion"}` (or similar; order and formatting not guaranteed).
