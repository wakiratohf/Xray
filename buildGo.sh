#!/bin/bash

# Set vars
export GOROOT=$(go env GOROOT)
export GOPATH=$(go env GOPATH)

# Set path
export PATH="$GOROOT/bin:$PATH"
export PATH=$GOROOT/bin:$GOPATH/bin:$PATH

if [ ! -d "$GOROOT/src" ]; then
    git clone https://github.com/golang/go.git $GOROOT
fi

GO_VERSION="go$(sed -n -E 's/^go (.*)/\1/p' XrayCore/go.mod)"

pushd $GOROOT
git checkout "$GO_VERSION"
cd src
chmod +x make.bash
sudo ./make.bash
popd

./buildXrayCore.sh
./buildXrayHelper.sh
