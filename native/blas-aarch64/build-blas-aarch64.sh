#!/usr/bin/env bash
set -euo pipefail

OPENBLAS_COMMIT=8795fc7985635de1ecf674b87e2008a15097ffab
NETLIB_COMMIT=52b3a8beb23ee98d26eb66fa67d904c704e01e45
BUILD_IMAGE=quay.io/pypa/manylinux2014_aarch64@sha256:d4c40df238d6ec0ee91213f4797ad26f05d7d82947bcb61c40d1fdc13d0e16d5
OPENBLAS_SOURCE=${OPENBLAS_SOURCE:-https://github.com/OpenMathLib/OpenBLAS.git}
NETLIB_SOURCE=${NETLIB_SOURCE:-https://github.com/fommil/netlib-java.git}
OUTPUT=${1:-dist}
JOBS=${JOBS:-$(getconf _NPROCESSORS_ONLN)}

if [[ $(uname -m) != aarch64 ]]; then
  echo "This package must be built natively on AArch64." >&2
  exit 1
fi
for command in git mvn javac java jar podman file readelf sha256sum; do
  command -v "$command" >/dev/null || { echo "Missing command: $command" >&2; exit 1; }
done

BUILD=$(mktemp -d)
trap 'rm -rf "$BUILD"' EXIT

git clone --quiet "$OPENBLAS_SOURCE" "$BUILD/OpenBLAS"
git -C "$BUILD/OpenBLAS" checkout --quiet "$OPENBLAS_COMMIT"
podman run --rm --userns=keep-id -e JOBS="$JOBS" \
  -v "$BUILD:/work:Z" -w /work/OpenBLAS "$BUILD_IMAGE" \
  bash -lc 'make -s -j"$JOBS" libs TARGET=ARMV8 BINARY=64 DYNAMIC_ARCH=0 \
    NOFORTRAN=1 NO_LAPACK=1 NO_SHARED=1 USE_THREAD=1 CFLAGS="-O3 -fPIC"'
OPENBLAS_ARCHIVES=("$BUILD"/OpenBLAS/libopenblas_*-r*.a)
if [[ ${#OPENBLAS_ARCHIVES[@]} -ne 1 || ! -f ${OPENBLAS_ARCHIVES[0]} ]]; then
  echo "Expected exactly one OpenBLAS static archive." >&2
  exit 1
fi

git clone --quiet "$NETLIB_SOURCE" "$BUILD/netlib-java"
git -C "$BUILD/netlib-java" checkout --quiet "$NETLIB_COMMIT"

# The archived upstream build targets Java 6 and Lombok 1.12.2. These build-only
# substitutions make its generator work on the JDK 11 used by spark-lp.
sed -i 's/<version>1\.12\.2<\/version>/<version>1.18.32<\/version>/g' \
  "$BUILD/netlib-java/pom.xml" "$BUILD/netlib-java/generator/pom.xml"
sed -i 's/<source>1\.6<\/source>/<source>1.8<\/source>/g; s/<target>1\.6<\/target>/<target>1.8<\/target>/g' \
  "$BUILD/netlib-java/pom.xml" "$BUILD/netlib-java/generator/pom.xml"

M2="$BUILD/m2"
# The generator is intentionally outside the upstream reactor but is required
# by the core and native modules.
mvn -q -f "$BUILD/netlib-java/generator/pom.xml" -Dmaven.repo.local="$M2" \
  -Dgpg.skip=true -DskipTests install
mvn -q -f "$BUILD/netlib-java/pom.xml" -Dmaven.repo.local="$M2" \
  -Dgpg.skip=true -DskipTests install

# native-maven-plugin still invokes javah, removed after JDK 9. The generated C
# declares its JNI entry points, and javac -h below creates the required header.
mkdir -p "$BUILD/bin"
printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail' \
  'while (($#)); do' \
  '  if [[ "$1" == -d ]]; then mkdir -p "$2"; shift 2; else shift; fi' \
  'done' >"$BUILD/bin/javah"
chmod +x "$BUILD/bin/javah"

NATIVE="$BUILD/netlib-java/native_system/xbuilds/linux-aarch64"
PATH="$BUILD/bin:$PATH" mvn -q -f "$NATIVE/pom.xml" -Dmaven.repo.local="$M2" \
  -Dgpg.skip=true -DskipTests generate-sources

mkdir -p "$NATIVE/target/header-classes" "$NATIVE/target/native/javah"
CP="$M2/com/github/fommil/netlib/core/1.2-SNAPSHOT/core-1.2-SNAPSHOT.jar:$M2/com/github/fommil/netlib/native_system-java/1.2-SNAPSHOT/native_system-java-1.2-SNAPSHOT.jar:$M2/com/github/fommil/jniloader/1.1/jniloader-1.1.jar:$M2/net/sourceforge/f2j/arpack_combined_all/0.1/arpack_combined_all-0.1.jar"
javac -proc:none -h "$NATIVE/target/native/javah" -d "$NATIVE/target/header-classes" \
  -cp "$CP" \
  "$BUILD/netlib-java/native_system/java/target/netlib-native/com/github/fommil/netlib/NativeSystemBLAS.java"

JAVA_INCLUDE="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}/include"
cp -R "$JAVA_INCLUDE" "$BUILD/jni-include"
LIBRARY=netlib-native_blas-linux-aarch64.so
podman run --rm --userns=keep-id -v "$BUILD:/work:Z" -w /work "$BUILD_IMAGE" \
  bash -lc 'gcc -shared -fPIC -O3 -Wall -fdata-sections -ffunction-sections \
    -I/work/jni-include -I/work/jni-include/linux \
    -I/work/netlib-java/native_system/xbuilds/linux-aarch64/target/native/javah \
    -I/work/netlib-java/netlib/JNI -I/work/OpenBLAS \
    /work/netlib-java/native_system/xbuilds/linux-aarch64/target/netlib-native/com_github_fommil_netlib_NativeSystemBLAS.c \
    /work/netlib-java/netlib/JNI/netlib-jni.c \
    /work/OpenBLAS/libopenblas_*-r*.a -pthread -lm -ldl \
    -Wl,-z,defs -Wl,-s -Wl,--version-script=/work/netlib-java/netlib/symbol.map \
    -Wl,--gc-sections -o /work/netlib-native_blas-linux-aarch64.so'

mkdir -p "$OUTPUT" "$BUILD/package/META-INF" "$BUILD/probe"
cp "$BUILD/$LIBRARY" "$OUTPUT/$LIBRARY"
cp "$BUILD/$LIBRARY" "$BUILD/package/$LIBRARY"
cp "$BUILD/netlib-java/LICENSE.txt" "$BUILD/package/META-INF/LICENSE.netlib-java"
cp "$BUILD/OpenBLAS/LICENSE" "$BUILD/package/META-INF/LICENSE.OpenBLAS"

cat >"$BUILD/MANIFEST.MF" <<EOF
Manifest-Version: 1.0
Implementation-Title: netlib-java BLAS Linux AArch64 JNI
Implementation-Version: 1.1-aarch64-openblas-0.3.29
Netlib-Java-Commit: $NETLIB_COMMIT
OpenBLAS-Commit: $OPENBLAS_COMMIT
Build-Image: $BUILD_IMAGE
Built-Architecture: aarch64

EOF
ARTIFACT="$OUTPUT/netlib-native_blas-linux-aarch64-1.1-openblas-0.3.29.jar"
jar cfm "$ARTIFACT" "$BUILD/MANIFEST.MF" -C "$BUILD/package" .

javac -cp "$CP" -d "$BUILD/probe" BlasProbe.java
OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1 java \
  -Dcom.github.fommil.netlib.NativeSystemBLAS.natives="$LIBRARY" \
  -cp "$ARTIFACT:$CP:$BUILD/probe" BlasProbe

file "$BUILD/$LIBRARY"
readelf -d "$BUILD/$LIBRARY" | grep NEEDED
sha256sum "$OUTPUT/$LIBRARY" "$ARTIFACT"
