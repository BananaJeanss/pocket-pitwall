#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
out_dir="$(mktemp -d)"
trap 'rm -rf "$out_dir"' EXIT
java -m jdk.compiler/com.sun.tools.javac.Main -d "$out_dir" \
  core/src/main/java/dev/bananajeans/pitwall/core/*.java \
  core/src/test/java/dev/bananajeans/pitwall/core/TelemetryTest.java
java -cp "$out_dir" dev.bananajeans.pitwall.core.TelemetryTest
