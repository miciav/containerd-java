plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "containerd-java"

// libcni-java is built from source when it sits next to this repository, which is how the CNI
// source set resolves it: Gradle substitutes the dependency with the included build, so there is
// nothing to publish first and no stale artifact to build against. Without the directory the
// dependency falls back to a repository, and the CNI source set will not compile until
// libcni-java is published somewhere it can be found — the core and its tests are unaffected.
val libcni = file("../libcni-java")
if (libcni.isDirectory) {
    includeBuild(libcni)
}
