#!/bin/bash
#

rm -rf dist/ target/
mvn clean package test install

# Generate javadoc
#mvn javadoc:javadoc

# 3rd party dependencies
mvn dependency:copy-dependencies dependency:tree versions:display-dependency-updates

cd dist && unzip *.zip
