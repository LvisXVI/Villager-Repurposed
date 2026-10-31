#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p run
echo "eula=true" > run/eula.txt
cat > run/server.properties <<'EOF'
level-name=vp-role-test
generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:stone","height":59},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains","structures":{"structures":{}}}
flat-world=true
gamemode=creative
force-gamemode=true
spawn-monsters=false
max-players=1
online-mode=false
enable-rcon=false
pause-when-empty-seconds=-1
EOF
export VP_AUTOTEST="${VP_AUTOTEST:-headless}"
./gradlew runServer --no-daemon "$@"
