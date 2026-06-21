# syntax=docker/dockerfile:1

# ---- Build stage -------------------------------------------------------------
# Builds the Parseable Android APK from source. Mirrors the GitHub Actions CI:
# JDK 17, Android SDK (compileSdk 35), Gradle 8.9, with the wrapper generated
# on the fly (gradle-wrapper.jar is gitignored). The app's build.gradle.kts
# derives its version from `git log`, so git + the .git directory are required.
#
# Pinned to linux/amd64: the Android toolchain (aapt2) ships only x86-64 Linux
# binaries. On an arm64 host (Apple Silicon) a native arm64 image can't exec
# them ("rosetta error: failed to open elf .../ld-linux-x86-64.so.2"); a full
# amd64 image gives Rosetta/QEMU a complete x86-64 userland to emulate. On an
# amd64 host this is native. Override with --build-arg BUILD_PLATFORM=... .
ARG BUILD_PLATFORM=linux/amd64
FROM --platform=${BUILD_PLATFORM} eclipse-temurin:17-jdk-jammy AS build

# Versions (override at build time with --build-arg if needed)
ARG GRADLE_VERSION=8.9
ARG ANDROID_CMDLINE_TOOLS_VERSION=11076708
ARG ANDROID_PLATFORM=35
ARG ANDROID_BUILD_TOOLS=35.0.0
# Gradle task to run: assembleDebug (default) or assembleRelease
ARG GRADLE_TASK=assembleDebug

ENV ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_HOME=/opt/android-sdk \
    GRADLE_USER_HOME=/root/.gradle \
    DEBIAN_FRONTEND=noninteractive

# Base tooling: git (version stamp), unzip/curl (fetching SDK + Gradle)
RUN apt-get update && \
    apt-get install -y --no-install-recommends git unzip curl ca-certificates && \
    rm -rf /var/lib/apt/lists/*

# Android SDK command-line tools
RUN mkdir -p "${ANDROID_SDK_ROOT}/cmdline-tools" && \
    curl -fsSL "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_VERSION}_latest.zip" -o /tmp/cmdline-tools.zip && \
    unzip -q /tmp/cmdline-tools.zip -d "${ANDROID_SDK_ROOT}/cmdline-tools" && \
    mv "${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools" "${ANDROID_SDK_ROOT}/cmdline-tools/latest" && \
    rm /tmp/cmdline-tools.zip

ENV PATH="${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools:${PATH}"

# Accept licenses and install required SDK packages
RUN yes | sdkmanager --licenses >/dev/null && \
    sdkmanager --install \
      "platform-tools" \
      "platforms;android-${ANDROID_PLATFORM}" \
      "build-tools;${ANDROID_BUILD_TOOLS}" >/dev/null

# Gradle (used to generate the wrapper, then the wrapper drives the build)
RUN curl -fsSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o /tmp/gradle.zip && \
    unzip -q /tmp/gradle.zip -d /opt && \
    rm /tmp/gradle.zip
ENV PATH="/opt/gradle-${GRADLE_VERSION}/bin:${PATH}"

WORKDIR /workspace

# Copy the full project (including .git for the version stamp; see .dockerignore)
COPY . .

# Generate the Gradle wrapper, then build the APK
RUN gradle wrapper --gradle-version "${GRADLE_VERSION}" && \
    ./gradlew --no-daemon "${GRADLE_TASK}"

# ---- Export stage ------------------------------------------------------------
# Minimal final image holding just the built APK(s) under /apks.
# Extract them to ./out with:
#   docker build --target export --output type=local,dest=./out .
# (A scratch image has no shell, so use --output, not docker create/cp.)
FROM scratch AS export
COPY --from=build /workspace/app/build/outputs/apk/ /apks/
