#!/usr/bin/env bash
# Hidden judge: the clarified target must be updated and the other config untouched.
set -e

grep -q '^request.timeout=30$' server.properties || {
  echo "server.properties request.timeout is not 30" >&2
  exit 1
}
grep -q '^max.connections=64$' server.properties || {
  echo "server.properties max.connections was modified" >&2
  exit 1
}
grep -q '^connect.timeout.ms=2000$' client.properties || {
  echo "client.properties connect.timeout.ms was modified" >&2
  exit 1
}
grep -q '^retry.count=3$' client.properties || {
  echo "client.properties retry.count was modified" >&2
  exit 1
}
echo ok
