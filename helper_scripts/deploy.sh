#!/bin/bash

if [ $# == 0 ] || [ "$1" == "--help" ]
then
    echo "Usage: sudo $0 <JAR_FILE_NAME>"
    exit 0
fi

if [ "$EUID" -ne 0 ]; then
  echo "Deploy can only be done as root."
  exit 1
fi

JAR_FILE=$1

mv $JAR_FILE /var/www/yt-dlp-web/
cd /var/www/yt-dlp-web
supervisorctl stop yt-dlp-web
rm yt.jar
ln -s $JAR_FILE yt.jar
supervisorctl start yt-dlp-web

exit 0
