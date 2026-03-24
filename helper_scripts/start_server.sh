#!/bin/bash

cleanup() {
    echo "Cleanup called"
    kill "$JAVA_PID"
    wait "$JAVA_PID"
    echo "Java stopped"
}

trap cleanup TERM INT SIGINT SIGTERM

cd /var/www/yt-dlp-web

java -jar yt.jar server &
JAVA_PID=$!

wait "$JAVA_PID"
