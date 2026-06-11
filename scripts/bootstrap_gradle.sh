#!/bin/bash
set -e

# Version of Gradle to install
GRADLE_VERSION="8.5"
DIST_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
WRAPPER_DIR="gradle/wrapper"
WRAPPER_PROPERTIES="${WRAPPER_DIR}/gradle-wrapper.properties"
WRAPPER_JAR="${WRAPPER_DIR}/gradle-wrapper.jar"

echo "Bootstrapping Gradle Wrapper ${GRADLE_VERSION}..."

# Create directory
mkdir -p "$WRAPPER_DIR"

# Create gradle-wrapper.properties
cat > "$WRAPPER_PROPERTIES" <<EOL
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip
networkTimeout=10000
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
validateDistributionUrl=true
EOL

# Download gradle-wrapper.jar (using a small minimal jar or just downloading the full dist? 
# Actually, the standard way is to use the 'wrapper' task, but we don't have gradle.
# So we will download a small bootstrap script or the full zip.
# Alternative: simple curl to download the gradle binary to a temp location and run it to generate the wrapper.

TEMP_DIR=$(mktemp -d)
echo "Downloading Gradle..."
curl -L "$DIST_URL" -o "${TEMP_DIR}/gradle.zip"

echo "Unzipping..."
unzip -q "${TEMP_DIR}/gradle.zip" -d "${TEMP_DIR}"

GRADLE_BIN="${TEMP_DIR}/gradle-${GRADLE_VERSION}/bin/gradle"

echo "Generating Wrapper..."
"$GRADLE_BIN" wrapper --gradle-version "$GRADLE_VERSION"

# CRITICAL: Stop the daemon we just started!
# Otherwise, subsequent builds might try to reuse this daemon, 
# which is running from the temp dir we are about to delete.
"$GRADLE_BIN" --stop

# Cleanup
rm -rf "$TEMP_DIR"

echo "Gradle Wrapper installed. You can now use ./gradlew"
chmod +x gradlew
