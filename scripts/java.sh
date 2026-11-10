# Sourced by the run scripts: sets JAVA to a Java 25 (or newer) runtime, which the backend is compiled for.
# Looks at JAVA_HOME, then JDKs unpacked under ~/.jdks, then java on the PATH.
java_major() { "$1" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p'; }
JAVA=
for candidate in ${JAVA_HOME:+"$JAVA_HOME/bin/java"} $(ls -d "$HOME"/.jdks/jdk-*/bin/java 2>/dev/null | sort -rV) "$(command -v java || true)"; do
  if [[ -x $candidate && $(java_major "$candidate") -ge 25 ]]; then JAVA=$candidate; break; fi
done
if [[ -z $JAVA ]]; then
  echo "Java 25 or newer is required. Install a JDK 25 (e.g. Eclipse Temurin) and set JAVA_HOME, or unpack it under ~/.jdks." >&2
  exit 1
fi
