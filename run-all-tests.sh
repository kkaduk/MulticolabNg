#!/bin/bash
# tests/run-all-tests.sh

set -e

echo "=== Running Unit Tests ==="
sbt "testOnly *LLMAgentPlanningSpec"

echo "=== Running Integration Tests ==="
sbt "testOnly *LLMAgentSpec"

echo "=== Running Load Tests ==="
sbt "testOnly *LLMAgentTelemetrySpec"

# echo "=== Running Benchmarks ==="
# sbt "jmh:run -i 3 -wi 2 -f1 -t1 .*LLMAgentBenchmark.*"

# echo "=== Generating Coverage Report ==="
# sbt clean coverage test coverageReport

echo "=== All tests passed ==="
