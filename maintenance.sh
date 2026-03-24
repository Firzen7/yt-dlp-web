#!/bin/bash

# This script removed old files from yt-dlp-web downloads directory,
# and it also updates yt-dlp utility.
# It is to be configured to run regularly using cron.

TARGET="/tmp/yt-dlp-web"
# TARGET="/home/firzen/tmp"

find "$TARGET" -mindepth 1 -maxdepth 1 -type d -mtime +3 -print

yt-dlp -U

exit 0
