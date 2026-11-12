#!/usr/bin/env python3
"""Run one credential-injected seed analysis; persist only numeric telemetry."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--jar', type=Path, required=True)
    args = parser.parse_args()
    telemetry = args.output.with_suffix('.telemetry.json')
    if args.output.exists() or telemetry.exists():
        parser.error('Output or telemetry already exists')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    command = [
        'sealbox', 'run', '-p', 'paypal-hackathon', '--redact', '--', 'env',
        'LLM_SEED_RUN=true', f'LLM_SEED_OUTPUT_FILE={args.output}',
        'LLM_MODEL=gpt-6.1-sol', 'LLM_REASONING_EFFORT=low',
        'LLM_BATCH_SIZE=4', 'LLM_CONCURRENCY=10', 'LLM_DAILY_RUN_CAP=30',
        'DATABASE_URL=jdbc:h2:mem:evaluation', 'DB_USERNAME=sa', 'DB_PASSWORD=',
        'java', '-jar', str(args.jar), '--spring.main.web-application-type=none',
        '--logging.level.app.analysis=DEBUG',
    ]
    started = time.monotonic()
    # Discard raw logs: only these numeric provider/generator messages are retained.
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, timeout=180)
    tokens = [tuple(map(int, row)) for row in re.findall(
        r'Analysis tokens input=(\d+) output=(\d+) reasoning=(\d+)', result.stdout)]
    stages = re.findall(r'Analysis stage=(CLASSIFY|PROPOSE) latencyMs=(\d+) result=(Success|Failure)',
                        result.stdout)
    durations = re.findall(r'Seed analysis model=\S+ latencyMs=(\d+)', result.stdout)
    success = result.returncode == 0 and args.output.exists() and len(durations) == 1
    report = {
        'status': 'success' if success else 'failed',
        'processExitCode': result.returncode,
        'processWallMs': round((time.monotonic() - started) * 1000),
        'analysisLatencyMs': int(durations[0]) if len(durations) == 1 else None,
        'configuration': {'model': 'gpt-6.1-sol', 'reasoningEffort': 'low',
                          'batchSize': 4, 'concurrency': 10, 'dailyCallCap': 30},
        'usageResponses': len(tokens),
        'tokens': dict(zip(('input', 'output', 'reasoning'), map(sum, zip(*tokens)))) if tokens else None,
        'calls': [{'stage': stage, 'latencyMs': int(ms), 'result': state}
                  for stage, ms, state in stages],
        'usageLimit': 'Decoded responses only; HTTP failures and timeouts may have unreported usage. Reasoning is included in output tokens.',
        'analysisSha256': hashlib.sha256(args.output.read_bytes()).hexdigest() if args.output.exists() else None,
    }
    telemetry.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'status': report['status'], 'analysisLatencyMs': report['analysisLatencyMs'],
                      'usageResponses': len(tokens), 'tokens': report['tokens']}))
    if not success:
        raise SystemExit('Live analysis failed; no raw logs retained. Do not silently replace this attempt.')


if __name__ == '__main__':
    main()
