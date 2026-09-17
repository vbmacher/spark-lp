#!/usr/bin/env bash
set -euo pipefail

NETLIB_COMMIT=52b3a8beb23ee98d26eb66fa67d904c704e01e45
SOURCE=${1:-https://github.com/fommil/netlib-java.git}
OUTPUT=${2:-dist}

if [[ $(uname -m) != aarch64 ]]; then
  echo "This package must be built natively on AArch64." >&2
  exit 1
fi
for command in git mvn gcc javac jar sha256sum; do
  command -v "$command" >/dev/null || { echo "Missing command: $command" >&2; exit 1; }
done
if [[ ! -f /usr/include/openblas/cblas.h || ! -f /usr/include/openblas/lapacke.h ]]; then
  echo "OpenBLAS development headers are required under /usr/include/openblas." >&2
  exit 1
fi
if ! ldconfig -p | grep 'libopenblasp\.so\.0' >/dev/null; then
  echo "The pthread OpenBLAS runtime (libopenblasp.so.0) is required." >&2
  exit 1
fi

BUILD=$(mktemp -d)
trap 'rm -rf "$BUILD"' EXIT
git clone --quiet "$SOURCE" "$BUILD/netlib-java"
git -C "$BUILD/netlib-java" checkout --quiet "$NETLIB_COMMIT"

# The archived upstream build targets Java 6 and Lombok 1.12.2. These build-only
# substitutions make its generator work on the JDK 11 used by spark-lp.
sed -i 's/<version>1\.12\.2<\/version>/<version>1.18.32<\/version>/g' \
  "$BUILD/netlib-java/pom.xml" "$BUILD/netlib-java/generator/pom.xml"
sed -i 's/<source>1\.6<\/source>/<source>1.8<\/source>/g; s/<target>1\.6<\/target>/<target>1.8<\/target>/g' \
  "$BUILD/netlib-java/pom.xml" "$BUILD/netlib-java/generator/pom.xml"

M2="$BUILD/m2"
mvn -q -f "$BUILD/netlib-java/pom.xml" -Dmaven.repo.local="$M2" \
  -Dgpg.skip=true -DskipTests install

# native-maven-plugin still invokes javah, removed after JDK 9. The generated C
# declares its JNI entry points, and javac -h below creates the required headers.
mkdir -p "$BUILD/bin"
cat >"$BUILD/bin/javah" <<'JAVAH'
#!/usr/bin/env bash
set -euo pipefail
while (($#)); do
  if [[ "$1" == -d ]]; then mkdir -p "$2"; shift 2; else shift; fi
done
JAVAH
chmod +x "$BUILD/bin/javah"

NATIVE="$BUILD/netlib-java/native_system/xbuilds/linux-aarch64"
PATH="$BUILD/bin:$PATH" mvn -q -f "$NATIVE/pom.xml" -Dmaven.repo.local="$M2" \
  -Dgpg.skip=true -DskipTests generate-sources

mkdir -p "$NATIVE/target/header-classes" "$NATIVE/target/native/javah"
CP="$M2/com/github/fommil/netlib/core/1.2-SNAPSHOT/core-1.2-SNAPSHOT.jar:$M2/com/github/fommil/jniloader/1.1/jniloader-1.1.jar:$M2/net/sourceforge/f2j/arpack_combined_all/0.1/arpack_combined_all-0.1.jar"
javac -proc:none -h "$NATIVE/target/native/javah" -d "$NATIVE/target/header-classes" \
  -cp "$CP" \
  "$BUILD/netlib-java/native_system/java/target/netlib-native/com/github/fommil/netlib/NativeSystemBLAS.java" \
  "$BUILD/netlib-java/native_system/java/target/netlib-native/com/github/fommil/netlib/NativeSystemLAPACK.java"

JAVA_INCLUDE="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}/include"
LIBRARY=netlib-native_system-linux-aarch64.so
gcc -shared -fPIC -O3 -Wall -fdata-sections -ffunction-sections \
  -I"$JAVA_INCLUDE" -I"$JAVA_INCLUDE/linux" -I"$NATIVE/target/native/javah" \
  -I"$BUILD/netlib-java/netlib/JNI" -I/usr/include/openblas \
  "$NATIVE/target/netlib-native/com_github_fommil_netlib_NativeSystemBLAS.c" \
  "$NATIVE/target/netlib-native/com_github_fommil_netlib_NativeSystemLAPACK.c" \
  "$BUILD/netlib-java/netlib/JNI/netlib-jni.c" \
  -Wl,-s -Wl,--version-script="$BUILD/netlib-java/netlib/symbol.map" \
  -Wl,--gc-sections -lopenblasp -o "$BUILD/$LIBRARY"

mkdir -p "$OUTPUT"
cat >"$BUILD/MANIFEST.MF" <<EOF
Manifest-Version: 1.0
Implementation-Title: netlib-java native_system Linux AArch64 JNI
Implementation-Version: 1.1-aarch64-openblas
Netlib-Java-Commit: $NETLIB_COMMIT
Built-Architecture: aarch64
OpenBLAS-SONAME: libopenblasp.so.0

EOF
ARTIFACT="$OUTPUT/netlib-native_system-linux-aarch64-1.1-openblas-pthreads.jar"
mkdir -p "$BUILD/package/META-INF"
cp "$BUILD/$LIBRARY" "$BUILD/package/$LIBRARY"
cp "$BUILD/netlib-java/LICENSE.txt" "$BUILD/package/META-INF/LICENSE.netlib-java"
jar cfm "$ARTIFACT" "$BUILD/MANIFEST.MF" -C "$BUILD/package" .
file "$BUILD/$LIBRARY"
ldd "$BUILD/$LIBRARY"
sha256sum "$ARTIFACT"
