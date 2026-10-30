#!/usr/bin/env bash
# Downloads the pinned kind and kubectl into .tools/bin (gitignored) and checks each file's SHA-256 before using it.
# Pinned here instead of Homebrew: Homebrew can't pin versions, and an older kubectl on the PATH would win.
# kubectl has to be within one minor version of the cluster (kindest/node v1.37.0).
set -euo pipefail

KIND_VERSION=v0.33.0
KUBECTL_VERSION=v1.37.1

cd "$(dirname "$0")/.."
mkdir -p .tools/bin

case "$(uname -s)-$(uname -m)" in
  Darwin-arm64)
    platform=darwin-arm64
    kind_sha=0c8c7dbe5e23594a198b786c4bc13dacc101fa6196b0cb0b23a1ca44e61f4b4f
    kubectl_sha=fd65982c97ddad3106754b69ffa196d0e543aa591930ae52aed1adfb92f8c77f
    ;;
  Linux-x86_64)
    platform=linux-amd64
    kind_sha=aee6151561422756b764a4ae28e7f44cda5af5a9eead3cc9985112b1de8d8e0d
    kubectl_sha=65691ff77eb6fa44c908b77a1082c9f092c3b9733b5cefabec0d1104890e21a8
    ;;
  *)
    echo "No pinned checksums for $(uname -s)-$(uname -m); add them to this script." >&2
    exit 1
    ;;
esac

# Downloads to a temporary name, checks the checksum, and only then puts the file in place.
fetch() {
  local name=$1 url=$2 expected=$3
  local target=.tools/bin/$name
  curl -fsSL "$url" -o "$target.download"
  local actual
  actual=$(shasum -a 256 "$target.download" | cut -d' ' -f1)
  if [ "$actual" != "$expected" ]; then
    rm -f "$target.download"
    echo "Checksum mismatch for $name: expected $expected, got $actual" >&2
    exit 1
  fi
  mv "$target.download" "$target"
  chmod +x "$target"
  echo "$name: checksum OK"
}

fetch kind "https://github.com/kubernetes-sigs/kind/releases/download/$KIND_VERSION/kind-$platform" "$kind_sha"
fetch kubectl "https://dl.k8s.io/release/$KUBECTL_VERSION/bin/${platform%-*}/${platform#*-}/kubectl" "$kubectl_sha"

.tools/bin/kind version
.tools/bin/kubectl version --client
