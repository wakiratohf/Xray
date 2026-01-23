#!/bin/bash

    REFRESH="$1"
    SETUP="$2"
    DEST="../app/libs"

    prepare_go() {
      export GOPROXY=https://goproxy.io,direct
      echo "Install dependencies"
      if [[ -n "$SETUP" ]]; then
        rm -f go.mod go.sum
        go mod init XrayCore
        go mod edit -replace github.com/xtls/xray-core=./Xray-core
        go mod edit -replace github.com/xtls/libxray=./libXray
        go mod tidy
        go get golang.org/x/mobile
        go get google.golang.org/genproto
      fi
      local VERSION=$(awk -F ' ' '/golang.org\/x\/mobile/ {print $2}' go.mod)
      go install golang.org/x/mobile/cmd/gomobile@$VERSION
      go mod download
    }

    build_android() {
      echo "Building XrayCore for all ABIs"
      rm -f "$DEST/XrayCore.aar"
      gomobile init
      gomobile bind -o "$DEST/XrayCore.aar" -androidapi 26 -target "android/arm,android/arm64,android/386,android/amd64" -ldflags="-buildid=" -trimpath
    }

    refresh_dependencies() {
      echo "Gradle: refresh dependencies"
      ./gradlew --refresh-dependencies clean
    }


    pushd XrayCore
    prepare_go
    build_android
    popd

    if [[ -n "$REFRESH" ]]; then
      refresh_dependencies
    fi