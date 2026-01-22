#!/bin/bash
#

rm -rf dist/ target/
mvn clean package install

# Generate javadoc
mvn javadoc:javadoc

# Upgradable 3rd party libs
mvn dependency:tree org.codehaus.mojo:versions-maven-plugin:2.18.0:display-dependency-updates

cd dist && unzip *.zip
