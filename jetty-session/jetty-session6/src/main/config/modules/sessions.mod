# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables session management.
By enabling this module, it allows session management to be configured via the ini templates
created or by enabling other session-cache or session-store modules.
Without this module enabled,
the server may still use sessions, but their management cannot be configured.

[tags]
session

[depends]
server

[xml]
etc/sessions/id-manager.xml

[lib]
/ib/jetty-session-common-${jetty.version}.jar
lib/jetty-session6-${jetty.version}.jar

[ini-template]
## The name to uniquely identify this server instance
#jetty.sessionIdManager.workerName=node1

## Period between runs of the session scavenger (in seconds)
#jetty.sessionScavengeInterval.seconds=600