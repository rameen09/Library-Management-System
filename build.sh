#!/bin/bash
# Build and test script for the Library Management System.
# Requires: JDK 17+, and the jars listed below present in lib/.
#
# On Ubuntu/Debian, the required jars can be installed with:
#   sudo apt-get install default-jdk libxerial-sqlite-jdbc-java junit4
# then copied into lib/ from /usr/share/java/.
#
# Usage:
#   ./build.sh          # compiles everything and runs the tests
#   ./build.sh run       # compiles and runs the Main demo

set -e

CP="build:lib/sqlite-jdbc.jar:lib/slf4j-api.jar:lib/slf4j-nop.jar:lib/junit4.jar:lib/hamcrest-core.jar"

echo "Compiling..."
rm -rf build
mkdir -p build
javac -cp "lib/sqlite-jdbc.jar" -d build src/main/java/library/*.java
javac -cp "$CP" -d build src/test/java/library/*.java

if [ "$1" == "run" ]; then
    echo "Running demo..."
    rm -f library.db
    java -cp "$CP" library.Main
else
    echo "Running tests..."
    java -cp "$CP" org.junit.runner.JUnitCore library.LibraryServiceTest
fi
