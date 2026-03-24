#!/bin/bash
# AccessAbility - Run Script
# Make sure you ran compile.sh first!!
#
# Usage: bash run.sh

if [ ! -d "out" ] || [ -z "$(ls -A out 2>/dev/null)" ]; then
    echo "No compiled files found! Run compile.sh first."
    bash compile.sh
fi

echo "Starting AccessAbility..."
java -cp out Main
