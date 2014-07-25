#!/bin/bash
#
# Copyright 2014 by Cloudsoft Corporation Limited
#
set -x # DEBUG

if [ -z "${JAVA_HOME}" ] ; then
    JAVA=$(which java)
else
    JAVA=${JAVA_HOME}/bin/java
fi

if [ ! -x "${JAVA}" ] ; then
  echo Cannot find java. Set JAVA_HOME or add java to path.
  exit 1
fi

${JAVA} -Xms256m -Xmx1024m -XX:MaxPermSize=1024m \
    -Daws-ec2.identity=$1 -Daws-ec2.credential=$2 \
    -cp ec2cleanup-0.2.0-PUSHTECHNOLOGY-jar-with-dependencies.jar debug.SignatureTest

