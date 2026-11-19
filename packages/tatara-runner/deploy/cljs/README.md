# Offline Itonami ClojureScript toolchain

Provision this in existing Tatara runner guests using `./install.sh`. Linux
aarch64 is the eligible runner platform. Darwin arm64 is supported for local
verification. The default installation is `/opt/tatara-toolchains/cljs-jvm-v1`.
Choose an absolute target with argument one. Provisioning can read a staged
archive and Maven cache with argument two. The installer verifies each download
or cached file before extracting it.

The bundle uses Temurin JDK 21.0.13+9, Clojure CLI 1.12.5.1654, and Node
22.23.5. The ordered Maven JAR list pins the ClojureScript 1.12.145 classpath
and its resolved transitive dependencies by SHA-256. The CLI uses that
classpath through `-Scp`, with the app's generated source directory. It rejects
an app dependency declaration other than the one this toolchain was built
for. Changes to the resolved classpath require a new toolchain version.

An Itonami pipeline should declare `:tier :t2 :toolchain :cljs-jvm-v1
:network :none :quorum 2` and run the installed `tatara-cljs-test` from the
source snapshot root. The wrapper runs the existing `npm --prefix app test`.
The coordinator's `:runner-toolchains` map associates each qualified runner
DID with its toolchain keywords, such as `#{:cljs-jvm-v1}`. Add a DID only
after the installation has passed a compiler smoke test with networking
disabled. A runner without that assignment can still take unqualified T2
jobs.
