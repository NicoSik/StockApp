#!/bin/sh
# ---------------------------------------------------------------------------
# Starts Ticker on macOS or Linux. The Windows equivalent is run.bat.
#
# Checks the things that actually go wrong - missing .env, no JDK 17+, a port
# already in use - and reports each one with the fix, then hands off to the
# Maven wrapper. No Maven installation is required; the wrapper fetches it on
# first use.
#
#   ./run.sh                          start the app
#   ./run.sh --package                build demo/target/ticker.jar instead
#   ./run.sh --package --skip-tests   ...without running the tests
# ---------------------------------------------------------------------------
set -eu

root=$(cd "$(dirname "$0")" && pwd)
package=false
skip_tests=false
for arg in "$@"; do
    case "$arg" in
        --package) package=true ;;
        --skip-tests) skip_tests=true ;;
        *) echo "Unknown option: $arg (expected --package or --skip-tests)" >&2; exit 2 ;;
    esac
done

ok()   { printf '  \033[32m[ok]\033[0m   %s\n' "$1"; }
fail() { printf '  \033[31m[X]\033[0m    %s\n' "$1"; shift; for line in "$@"; do printf '         %s\n' "$line"; done; exit 1; }

echo
printf '  \033[36mTicker\033[0m\n'
echo '  --------------------------------------------------'

# --- 1. Configuration -------------------------------------------------------

[ -f "$root/.env" ] || fail '.env is missing' \
    'Copy .env.example to .env and fill in your credentials:' \
    '  cp .env.example .env'
ok '.env found'

# Read SERVER_PORT without sourcing the rest of the file into this shell.
port=$(sed -n 's/^[[:space:]]*SERVER_PORT[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$root/.env" | head -n 1)
port=${port:-9090}

# --- 2. Java ----------------------------------------------------------------

# Javalin 7 is compiled for Java 17. Reading the JDK's `release` file is faster
# than starting a JVM to ask it.
jdk_version() {
    if [ -f "$1/release" ]; then
        sed -n 's/^JAVA_VERSION="\{0,1\}\([0-9][0-9]*\).*/\1/p' "$1/release" | head -n 1
    fi
}

jdk=""
if [ -n "${JAVA_HOME:-}" ] && [ "$(jdk_version "$JAVA_HOME")" -ge 17 ] 2>/dev/null; then
    jdk=$JAVA_HOME
elif [ -x /usr/libexec/java_home ] && candidate=$(/usr/libexec/java_home -v 17+ 2>/dev/null); then
    jdk=$candidate
elif command -v java >/dev/null 2>&1; then
    # A JDK on PATH but not registered anywhere, e.g. Homebrew's openjdk.
    candidate=$(cd "$(dirname "$(readlink -f "$(command -v java)")")/.." && pwd)
    [ "$(jdk_version "$candidate")" -ge 17 ] 2>/dev/null && jdk=$candidate
fi

[ -n "$jdk" ] || fail 'no JDK 17 or newer found' \
    'Javalin 7 requires Java 17+. Install a JDK, for example:' \
    '  brew install openjdk@21'
export JAVA_HOME="$jdk"
ok "JDK $(jdk_version "$jdk") at $jdk"

# --- 3. Port ----------------------------------------------------------------

if command -v lsof >/dev/null 2>&1; then
    holder=$(lsof -nP -iTCP:"$port" -sTCP:LISTEN 2>/dev/null | awk 'NR == 2 { print $1 " (pid " $2 ")" }')
    [ -z "$holder" ] || fail "port $port is already in use by $holder" \
        'Stop that process, or set a different SERVER_PORT in .env.'
fi
ok "port $port is free"

# --- 4. Go ------------------------------------------------------------------

echo '  --------------------------------------------------'
cd "$root/demo"

if [ "$package" = true ]; then
    printf '  \033[36mBuilding target/ticker.jar ...\033[0m\n'
    if [ "$skip_tests" = true ]; then ./mvnw clean package -DskipTests; else ./mvnw clean package; fi
    echo
    printf '  \033[32mBuilt demo/target/ticker.jar - run it with:\033[0m\n'
    echo '    java -jar demo/target/ticker.jar'
else
    printf '  \033[36mStarting on http://localhost:%s  (Ctrl-C to stop)\033[0m\n\n' "$port"
    exec ./mvnw compile exec:java
fi
