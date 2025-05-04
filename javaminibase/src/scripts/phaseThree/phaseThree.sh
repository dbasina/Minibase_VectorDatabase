#!/bin/bash

# Invoke the JAR file's DbmsEntry.main() with provided arguments
# Ensure java points to a JDK higher than openjdk 21 2023-09-19 (Provided JAR was compiled on that version)

# Sample usage
# ./phaseThree.sh

java -cp cse510_project.jar scripts.phaseThree.DbmsEntry "$0" "$1" "$2" "$3" "$4"