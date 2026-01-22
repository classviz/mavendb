#!/bin/bash
#

rm -rf dist/ target/
mvn clean package install

# Generate javadoc
#mvn javadoc:javadoc

# 3rd party dependencies
mvn dependency:copy-dependencies
mvn versions:display-dependency-updates

cd dist && unzip *.zip
