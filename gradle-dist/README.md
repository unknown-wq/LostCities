# Gradle 9.6.1 distribution (offline)

Minecraft **26.2** needs Java 25, which needs **Gradle 9.x** (Gradle 8.x cannot
run on Java 25). In this environment the Gradle wrapper cannot download its
distribution — `services.gradle.org` redirects to GitHub release assets, which
the egress policy blocks (HTTP 403). So the distribution is vendored here as a
multi-volume RAR (each part is < 100 MB to stay under GitHub's file-size limit).

## Install

```sh
./gradle-dist/install.sh          # extracts to /opt/gradle-9.6.1 and prints the path
export PATH=/opt/gradle-9.6.1/bin:$PATH
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
gradle --version                  # Gradle 9.6.1
```

Then build `lostbuildings` with the system Gradle — the module ships no wrapper,
so this is the only way to build it here:

```sh
cd lostbuildings
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 gradle build --no-daemon
```

Jars land in `lostbuildings/build/libs/`. This does **not** apply to `1.21/`, the
read-only Forge 1.20 source mod, which pins Gradle 7.6.4 and Java 17 in its own
wrapper.

## Files

- `gradle-9.6.1-bin.part1.rar` … `part5.rar` — volumes of `gradle-9.6.1-bin.zip`.
- `install.sh` — unpacks the volumes and unzips to `/opt/gradle-9.6.1`.

Requires `unrar` (`sudo apt-get install -y unrar`).
