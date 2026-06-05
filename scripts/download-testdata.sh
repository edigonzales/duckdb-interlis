#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

EXT_DIR="$REPO_ROOT/testdata/external"
mkdir -p "$EXT_DIR"

echo "Downloading external test data to $EXT_DIR ..."
cd "$EXT_DIR"

curl -L -o ch.so.afu.abbaustellen.xtf.zip \
    'https://files.geo.so.ch/ch.so.afu.abbaustellen/aktuell/ch.so.afu.abbaustellen.xtf.zip'
unzip -o ch.so.afu.abbaustellen.xtf.zip -d ch.so.afu.abbaustellen

curl -L -o ch.so.afu.abbaustellen.relational.xtf.zip \
    'https://files.geo.so.ch/ch.so.afu.abbaustellen.relational/aktuell/ch.so.afu.abbaustellen.relational.xtf.zip'
unzip -o ch.so.afu.abbaustellen.relational.xtf.zip -d ch.so.afu.abbaustellen.relational

curl -L -o 2402.ch.so.arp.nutzungsplanung.kommunal.relational.xtf.zip \
    'https://files.geo.so.ch/ch.so.arp.nutzungsplanung.kommunal.relational/aktuell/2402.ch.so.arp.nutzungsplanung.kommunal.relational.xtf.zip'
unzip -o 2402.ch.so.arp.nutzungsplanung.kommunal.relational.xtf.zip -d ch.so.arp.nutzungsplanung.kommunal.relational

echo ""
echo "External test data downloaded to $EXT_DIR"
