#!/usr/bin/env bash
# Builds the Android engine (server/crates/dibe-jni) into the app's jniLibs, twice: NativeEngine loads
# the build the phone's CPU can run, since an instruction it lacks is a SIGILL nothing can catch.
#   fast    armv8.7a (dotprod + i8mm kernels)  -> libdibe.so
#   compat  armv8.2-a+dotprod                  -> libdibe_compat.so
# For each:
#   1. ggml static libraries for arm64, CPU only, into server/libs/android/<variant>/lib (kept: delete it to rebuild)
#   2. cargo build of dibe-jni for aarch64-linux-android against them, in server/target/android-<variant>
#   3. the stripped library into android/app/src/main/jniLibs/arm64-v8a
# ANDROID_NDK_HOME picks the NDK (default: r27d from Homebrew's commandlinetools). Extra arguments go
# to cargo, e.g. `--example engine` for the on-device check in dibe-jni/examples.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SERVER="$ROOT/server"
NDK="${ANDROID_NDK_HOME:-/opt/homebrew/share/android-commandlinetools/ndk/27.3.13750724}"
API=28
case "$(uname -s)" in
	Darwin) HOST=darwin-x86_64 ;;
	Linux) HOST=linux-x86_64 ;;
	*) echo "unsupported host $(uname -s)" >&2; exit 1 ;;
esac
TC="$NDK/toolchains/llvm/prebuilt/$HOST"
JNI_LIBS="$ROOT/android/app/src/main/jniLibs/arm64-v8a"
[ -d "$TC" ] || { echo "no NDK toolchain at $TC (set ANDROID_NDK_HOME)" >&2; exit 1; }
if ! command -v cargo >/dev/null; then
	for bin in "$HOME/.cargo/bin" "$HOME"/.rustup/toolchains/stable-*/bin; do
		[ -x "$bin/cargo" ] && PATH="$bin:$PATH" && break
	done
fi

target=aarch64-linux-android

# build <variant> <-march> <library file name> [cargo args...]
build() {
	local variant="$1" march="$2" name="$3"
	shift 3
	local lib_dir="$SERVER/libs/android/$variant/lib"
	local target_dir="$SERVER/target/android-$variant"
	local out="$JNI_LIBS/$name"
	if [ ! -f "$lib_dir/libggml.a" ] || [ ! -f "$lib_dir/libggml-base.a" ] || [ ! -f "$lib_dir/libggml-cpu.a" ]; then
		local version work
		version="$(tr -d '[:space:]' < "$SERVER/libs/ggml-version")"
		# server/libs/patches are for the Vulkan backend, which this build leaves out. One that touches
		# anything else must be applied here too, so stop rather than build without it.
		if grep -h '^+++ b/' "$SERVER"/libs/patches/*.patch | grep -vE '^\+\+\+ b/(CMakeLists.txt|src/ggml-vulkan/)'; then
			echo "a patch in server/libs/patches is not Vulkan-only; apply it in this script" >&2
			exit 1
		fi
		work="$SERVER/target/ggml-android-$variant"
		rm -rf "$work"
		mkdir -p "$work/src"
		git -C "$work/src" init -q
		git -C "$work/src" remote add origin https://github.com/ggml-org/ggml.git
		git -C "$work/src" fetch -q --depth 1 origin "$version"
		git -C "$work/src" checkout -q FETCH_HEAD
		# No OpenMP (the NDK's would have to ship with the app) and no Vulkan (no faster on the phones
		# measured, see docs/android-transcription.md). GGML_NATIVE=OFF: -march alone picks the kernels.
		cmake -S "$work/src" -B "$work/build" \
			-DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
			-DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM="android-$API" \
			-DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
			-DGGML_BUILD_EXAMPLES=OFF -DGGML_BUILD_TESTS=OFF -DGGML_NATIVE=OFF \
			-DGGML_OPENMP=OFF -DGGML_VULKAN=OFF \
			-DCMAKE_C_FLAGS="-march=$march" -DCMAKE_CXX_FLAGS="-march=$march"
		cmake --build "$work/build" --config Release --parallel
		mkdir -p "$lib_dir"
		for lib in ggml ggml-base ggml-cpu; do
			cp "$(find "$work/build" -name "lib$lib.a" | head -n 1)" "$lib_dir/"
		done
		rm -rf "$work"
	fi

	# max-page-size: devices with 16 KB pages (Android 15+) refuse a library aligned to 4 KB.
	(
		cd "$SERVER"
		GGML_LIB_DIR="$lib_dir" \
		CARGO_TARGET_DIR="$target_dir" \
		CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TC/bin/$target$API-clang" \
		CC_aarch64_linux_android="$TC/bin/$target$API-clang" \
		CXX_aarch64_linux_android="$TC/bin/$target$API-clang++" \
		AR_aarch64_linux_android="$TC/bin/llvm-ar" \
		BINDGEN_EXTRA_CLANG_ARGS_aarch64_linux_android="--sysroot=$TC/sysroot --target=$target$API" \
		CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-soname,$name" \
			cargo build --release --target "$target" -p dibe-jni --lib "$@"
	)

	mkdir -p "$JNI_LIBS"
	"$TC/bin/llvm-strip" --strip-unneeded -o "$out" "$target_dir/$target/release/libdibe.so"
	# libc++ is linked statically (ggml-rs-sys), so the app ships no libc++_shared.so.
	if "$TC/bin/llvm-readelf" -d "$out" | grep -q 'libc++_shared'; then
		echo "$out needs libc++_shared.so" >&2
		exit 1
	fi
	echo "built $out ($(du -h "$out" | cut -f1))"
}

build fast armv8.7a libdibe.so "$@"
build compat armv8.2-a+dotprod libdibe_compat.so "$@"
