#!/usr/bin/env bash
# Launch the Audiology2 Linux receiver.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
python3 - <<'PY'
import shutil, sys
for dep in ("sounddevice", "numpy"):
    try:
        __import__(dep)
    except ImportError:
        print(">> installing missing dependency:", dep, flush=True)
        import subprocess
        subprocess.check_call([sys.executable, "-m", "pip", "install", dep])
PY
exec python3 "$here/receiver.py" "$@"
