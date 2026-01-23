#!/bin/bash

ARCHS=(arm arm64 386 amd64)
DEST_BASE="../app/src/main/jniLibs"

prepare_go() {
  echo "Install dependencies"
  go mod download
}

build_android() {
  local TARGET_ARCH=$1
  local ABI=$2
  echo "Building XrayHelper for $ABI"
  local OUTPUT_DIR="$DEST_BASE/$ABI"
  mkdir -p "$OUTPUT_DIR"
  local OUTPUT="$OUTPUT_DIR/xrayhelper"
  rm -f "$OUTPUT"
  CGO_ENABLED=0 GOOS=linux GOARCH=$TARGET_ARCH \
    go build -v -o "$OUTPUT" \
    -ldflags "-s -w -buildid=" -buildvcs=false -trimpath ./main
}

pushd XrayHelper
prepare_go
build_android arm armeabi-v7a
build_android arm64 arm64-v8a
build_android 386 x86
build_android amd64 x86_64
popd
