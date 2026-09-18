#!/bin/sh
set -eu

exec /usr/local/bin/Carabiner --port "${CARABINER_PORT}"
