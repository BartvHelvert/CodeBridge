#!/usr/bin/env bash
# Runs the split-mode UI tests in WSL, on a virtual display, so nothing they open ever reaches the
# Windows desktop. Arguments go to Gradle, e.g.
#
#   wsl -d Ubuntu-24.04 -- ./src/ui-test/ui-test-wsl.sh --tests "me.helvert.codebridge.uitest.runtime.SplitModeViewsTest"
#
# The checkout is copied into WSL's own file system first -- building from /mnt/c is slow -- and
# Gradle's and Starter's downloads stay there between runs. The results are copied back to
# out/wsl/; the IDEs' own logs stay in WSL, at the path printed at the end.
#
# Needs, in the distribution: openjdk-25-jdk, xvfb, xauth, rsync, and the libraries the IDE and its
# browser load (libnss3, libgbm1, libgtk-3-0t64, libasound2t64, libxtst6 and the like).
set -uo pipefail

# The checkout: this script lives in src/ui-test.
source_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
work_dir="${CODEBRIDGE_WSL_WORK:-$HOME/codebridge}"

# What Windows builds and downloads stays behind -- .intellijPlatform holds Windows sandboxes, and
# Windows IDEs in a checkout older than the shared cache, which do not run here -- and WSL's own are
# kept, as excluded files are. The IDEs themselves are in WSL's own Gradle user home.
rsync -a --delete \
    --exclude .git --exclude .gradle --exclude .idea --exclude .kotlin --exclude .intellijPlatform \
    --exclude build --exclude out \
    "$source_dir/" "$work_dir/"

cd "$work_dir" || exit 1
# WSLg would show the IDEs on the Windows desktop: only the virtual display may be used. Its X
# display and Wayland socket are both dropped -- a Wayland client finds wayland-0 in
# XDG_RUNTIME_DIR without being told, and the IDE's runtime prefers Wayland -- so X11 on Xvfb is
# all that is left.
unset WAYLAND_DISPLAY DISPLAY PULSE_SERVER
XDG_RUNTIME_DIR="$(mktemp -d)"
export XDG_RUNTIME_DIR
trap 'rm -rf "$XDG_RUNTIME_DIR"' EXIT
xvfb-run --auto-servernum --server-args="-screen 0 1920x1080x24" \
    bash ./gradlew :ui-test:test --console=plain "$@"
status=$?

results="$source_dir/out/wsl"
mkdir -p "$results"
rsync -a --delete "$work_dir/src/ui-test/build/test-results/" "$results/test-results/" 2>/dev/null
rsync -a --delete "$work_dir/src/ui-test/build/reports/tests/" "$results/reports/" 2>/dev/null
echo
echo "Results: $results"
echo "IDE logs: \\\\wsl.localhost\\${WSL_DISTRO_NAME:-Ubuntu-24.04}$(echo "$work_dir/out/ide-tests/tests" | tr '/' '\\')"
exit $status
