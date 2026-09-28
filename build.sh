#!/bin/bash
cd "$(dirname "$0")" || exit 1

if [ -z "$JAVA_HOME" ] && [ -d /Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ]; then
	export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home
fi

bash gradlew :build "$@"
