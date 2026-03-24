#!/bin/bash
# AccessAbility - Compile Script
# Run this first before running the app
#
# Usage: bash compile.sh

echo ""
echo "╔══════════════════════════════════════╗"
echo "║   AccessAbility v2.0 - Compiler      ║"
echo "║   TSA Software Development 2026      ║"
echo "╚══════════════════════════════════════╝"
echo ""

# make output directory if it doesn't exist
mkdir -p out

echo "Compiling Java source files..."
javac -encoding UTF-8 -d out src/*.java

if [ $? -eq 0 ]; then
    echo ""
    echo "ROAR Compilation successful!"
    echo ""
    echo "To run the app:"
    echo "  bash run.sh"
    echo "  OR: java -cp out Main"
    echo ""
else
    echo ""
    echo "NO  Compilation FAILED"
    echo "Check the errors above and fix them lol"
    echo ""
    exit 1
fi
