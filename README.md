# CraftProxy

Reduce bandwidth use by caching chunk data

Version: 0.2.0

## Description

This plugin and local client allows chunk data to be cached locally by players. This reduces the bandwidth required for hosting the server. It also helps users who are on slower connections.

I am not sure what the status of this system is. It doesn't count as a plugin, since it has 2 parts.

Even when running the plugin, players who don't use the client proxy can still connect. However, they will use the full bandwidth.

The system can reduce bandwidth by 70-90% (after the 2nd login).

## Setup

### Server

Add the plugin file to the plugins folder

### Client

1. Start minecraft client and login

2. Double click on the client jar file

3. Enter login details

4. Enter the server location/port in the GUI

5. Press start on the GUI

6. Connect to localhost on the minecraft client

## Stable Builds

None yet

## Dev Builds

Warning: These may not be stable

Client
Plugin

Had it get the compress/decompression gain backwards.

## Build instructions:

```{sh}
mkdir out
<path>/javac.exe -cp <path to bukkit>/bukkit-0.0.1-SNAPSHOT.jar; com/raphfrk/*/*.java -d out
cd out
jar cvfm ../CraftProxyLiter.jar ../manifest.txt *
cd ..
zip -r CraftProxyLiter.jar . -i plugin.yml
```
