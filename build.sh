#!/bin/sh
#

rm -rf dist/ target/

# Compile first: the class dependency graph is generated from target/classes,
# and must exist before 'package' builds the dist zip
mvn clean compile -e || exit 1

# Class dependency graph (packaged into the dist zip as doc/project.svg); -e keeps only org.mavendb classes
jdeps -verbose:class -filter:none -e 'org\.mavendb\..*' -dotoutput target/jdeps target/classes || exit 1
dot -Tsvg target/jdeps/classes.dot -o target/jdeps/project.svg || exit 1

mvn package test install -e

# Generate javadoc
#mvn javadoc:javadoc

# 3rd party dependencies
mvn dependency:copy-dependencies dependency:tree versions:display-dependency-updates

cd dist && unzip *.zip
