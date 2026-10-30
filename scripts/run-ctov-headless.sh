#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p run
echo "eula=true" > run/eula.txt
cat > run/server.properties <<'EOF'
level-name=vp-ctov-headless
level-seed=8675309
generate-structures=true
gamemode=creative
force-gamemode=true
spawn-monsters=false
spawn-animals=true
max-players=1
online-mode=false
enable-rcon=false
pause-when-empty-seconds=-1
EOF
export VP_AUTOTEST="${VP_AUTOTEST:-ctov}"
./gradlew runServer --no-daemon "$@"
