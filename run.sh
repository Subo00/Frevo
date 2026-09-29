#!/bin/bash
# usage: run.sh session.zse log
cd /home/claude/Frevo; export JAVA_TOOL_OPTIONS=
CP=$(find Libraries -name '*.jar' | tr '\n' ':'); CP="bin:$CP:Components/Problems/PredatorPrey:Components/Representations/FullyMeshedNet"
J="javac -nowarn -encoding UTF-8 --release 17"
$J -cp "$CP" -d Components/Representations/FullyMeshedNet $(find Components/Representations/FullyMeshedNet -name '*.java') 2>&1 | grep -A3 error
$J -cp "$CP" -d Components/Problems/PredatorPrey Components/Problems/PredatorPrey/predatorprey/*.java 2>&1 | grep -A3 error
$J -cp "$CP" -d Components/Methods/AlternatingCoevolution Components/Methods/AlternatingCoevolution/alternatingcoevolution/*.java 2>&1 | grep -A3 error
xvfb-run -a java -cp "$CP" main.FrevoMain -s "$1" > "$2" 2>&1
