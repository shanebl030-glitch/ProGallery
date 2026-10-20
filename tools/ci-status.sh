#!/usr/bin/env bash
# Prints the latest GitHub Actions runs for this repo (id, status, conclusion, sha).
curl -s "https://api.github.com/repos/shanebl030-glitch/progallery/actions/runs?per_page=${1:-3}" | python3 -c "
import json,sys
d=json.load(sys.stdin)
for r in d.get('workflow_runs',[]): print(r['id'],r['status'],r['conclusion'],r['head_sha'][:7],r['display_title'][:50])
if 'workflow_runs' not in d: print(d)"
