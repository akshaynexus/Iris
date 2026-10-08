#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
export JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.2.1/libexec/openjdk.jdk/Contents/Home
./gradlew --stop
(cd ../mcopt && ./gradlew --stop)
exec python3 tools/game-harness/runner.py "$@"
