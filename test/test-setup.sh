debugPrintf() {
	printf "$@" | sed 's/^/::debug::/g' >&2
}

# Reads stdin and replaces one linefeed at the end of the input with "¶". Exits with non-zero status if that LF is
# missing.
# Piping a command to this function works around the removal of trailing LFs in command substitution
# [https://stackoverflow.com/a/15184414] and preserves meaningful LFs.
# For example, when the command is `jq --raw-output …` and the result is a string with ends with LF, that LF is
# preserved, and the LF added by jq (like line-based commands generally do) is replaced.
# This enables precise comparisons.
replaceLastLF() {
	perl -e '$/ = undef; $_ = <>; ($r = $_) =~ s{\n$}{}; die "missing trailing LF\n" if $r eq $_; printf "%s¶", $r'
}

assertVariableUnset() {
	local varName=$1; shift
	if [ "${!varName+defined}" = defined ]; then
		printf 'assertion error: %s (== "%s") is set\n' "$varName" "${!varName}" >&2
		return 1
	else
		printf 'ok: %s is not set\n' "$varName" >&2
	fi
}
# test this function:
(
	set +e
	unset x;   assertVariableUnset x >&/dev/null; statusUnset=$?
	x='';      assertVariableUnset x >&/dev/null; statusEmpty=$?
	x=foo;     assertVariableUnset x >&/dev/null; statusNonEmpty1=$?
	x=defined; assertVariableUnset x >&/dev/null; statusNonEmpty2=$?
	set -e
	[ $statusUnset -eq 0 ]
	[ $statusEmpty     -ne 0 ]
	[ $statusNonEmpty1 -ne 0 ]
	[ $statusNonEmpty2 -ne 0 ]
)

assertEquals() {
	debugPrintf 'assertEquals("%s", "%s")\n' "$1" "$2"
	if [ "$1" = "$2" ]; then
		printf 'ok: "%s" == "%s"\n' "$1" "$2" >&2
	else
		printf 'assertion error: "%s" != "%s"\n' "$1" "$2" >&2
		return 1
	fi
}

# The last argument is the expected result. All other arguments are treated as a command (with arguments)
# to be evaluated which outputs the actual result. Both results are then asserted to be equal.
#
# Also, unlike directly using the form
#   assertEquals "$(eval "$RESULT_GETTER 'key with spaces')" 'expected value'
# this function does not swallow a non-zero exit status in $RESULT_GETTER (but returns with that error
# status and skips the assertion).
#
# Of the arguments which form the command, only the first is evaled, the rest is treated literally.
# That odd behavior is useful for how the steps for similar test cases are written: They require
# different ways to get a result; for example, one needs `getJsonValue "$RESULT"` and another one
# `getJsonValue "$(<"$RESULT_FILE")`; this difference is moved to environment variables in order to keep
# the script identical, so it can be reused with a YAML anchor.
# Example usage: evalAndAssertEquals "$RESULT_GETTER" 'key with spaces' 'expected value'
# with RESULT_GETTER set to 'getJsonValue "$RESULT"'.
evalAndAssertEquals() {
	local getterFunction=$1; shift
	local args=("$@")
	local expectedValue="${args[@]:${#args[@]}-1}"
	args=("${args[@]:0:${#args[@]}-1}")

	local quotedArgs value
	quotedArgs=$(printf ' %q' "${args[@]}")

	# Call $getterFunction once to check its status only before the call to capture its output.
	# Otherwise, we'd get a misleading error from replaceLastLF if $getterFunction prints nothing and exits
	# with $NOT_FOUND_STATUS.
	eval "${getterFunction}${quotedArgs}" >/dev/null
	eval "value=\$(set -o pipefail; ${getterFunction}${quotedArgs} | replaceLastLF)"

	expectedValue="$(printf '%s\n' "$expectedValue" | replaceLastLF)"
	assertEquals "$value" "$expectedValue"
}

# Queries a value by calling command $1 with the remaining args and expects it to exit with status $NOT_FOUND_STATUS.
# The command is handled like in →evalAndAssertEquals (except that there is no expected value as last argument which
# is not passed to the command).
evalAndAssertUndefined() {
	local getterFunction=$1; shift
	local quotedArgs value st
	quotedArgs=$(printf ' %q' "$@")
	if eval "value=\$(${getterFunction}${quotedArgs})"; then
		printf 'assertion error: %s == "%s", expected undefined\n' "$*" "$value" >&2
		return 1
	elif [ ${st-$?} -eq "$NOT_FOUND_STATUS" ]; then
		printf 'ok: %s is undefined\n' "$*" >&2
	else
		# no error message, assuming failed $getterFunction has already written one
		return $st
	fi
}

# Extracts the value for key $2 from JSON $1.
getJsonValue() {
	local json=$1; shift
	local key=$1; shift
	debugPrintf 'getJsonValue("%s", "%s")\n' "$json" "$key"
	jq --arg k "$key" --raw-output '
			.[$k] as $v
			| if $v != null then
				$v
			elif (keys | index($k) != null) then #contains key $k (with a null or false value)
				""
			else
				(("key " + $k + " not found\n") | halt_error(env.NOT_FOUND_STATUS | tonumber))
			end' <<<"$json"
}

assertJsonDoesNotContainKey() {
	assertJsonContainsKeyEquals "$@" false
}

assertJsonContainsKey() {
	assertJsonContainsKeyEquals "$@" true
}

assertJsonContainsKeyEquals() {
	local json=$1; shift
	local key=$1; shift
	local expected=$1; shift
	debugPrintf 'assertJsonContainsKeyEquals("%s", "%s", "%s")\n' "$json" "$key" "$expected"
	local contains
	contains=$(jq --arg k "$key" --raw-output 'keys | index($k) != null' <<<"$json")
	assertEquals "$contains" "$expected"
}

# Encodes a key $1 in the output-style encoding (property key to output name).
encodeKey() {
	local key=$1; shift
	perl -pe 's=((?!_)[[:punct:]])= sprintf("-%04X", ord($1)) =ge; s=^=_=;' <<<"$key"
}

# Extracts the value for key <output-style encoding of $2> from JSON $2.
encodeKeyAndGetJsonValue() {
	local json=$1; shift
	local key=$1; shift
	local encodedKey
	encodedKey=$(encodeKey "$key")
	getJsonValue "$json" "$encodedKey" "$@"
}

# Prints the value of the environment variable set for key $1 (when not using a prefix).
getEnv() {
	local key=$1; shift
	debugPrintf 'getEnv("%s")\n' "$key"
	local st
	if printenv -- "$key"; then
		: # OK
	elif [ ${st-$?} -eq 1 ]; then
		printf 'environment variable %s not set\n' "$key" >&2
		return $NOT_FOUND_STATUS
	else
		# no error message, assuming printenv has already written one
		return $st
	fi
}

# Prints the value of the environment variable set for key $2 when using a prefix $1.
getEnvWithPrefix() {
	local prefix=$1; shift
	local key=$1; shift
	getEnv "${prefix}${key}" "$@"
}
