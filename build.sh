#!/bin/bash

# builds new jar file
./gradlew buildFatJar

# we go to the directory where aar files are created
cd build/libs/

# we get newest jar file
NEWEST_BUILD=$(ls -t | head -n 1)

# here we copy this new file into our releases directory
cp -i $NEWEST_BUILD ../../releases/

# finally, we add the file into git
git add ../../releases/$NEWEST_BUILD

exit 0
