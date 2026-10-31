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
max-tick-time=-1
EOF
export VP_AUTOTEST="${VP_AUTOTEST:-ctov}"
if [ "${VP_AUTOTEST}" = "headless" ]; then
	echo "run-ctov-headless.sh forces VP_AUTOTEST=ctov (got headless from the environment)" >&2
	export VP_AUTOTEST=ctov
fi
./gradlew runServer --no-daemon "$@"
