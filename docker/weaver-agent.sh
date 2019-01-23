#!/bin/sh -e

cd /opt/woken-validation

aspectj_version=1.9.2

curl -o aspectjweaver.jar http://search.maven.org/remotecontent?filepath=org/aspectj/aspectjweaver/${aspectj_version}/aspectjweaver-${aspectj_version}.jar
