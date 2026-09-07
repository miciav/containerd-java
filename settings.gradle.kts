plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "containerd-java"

// libcni-java comes from GitHub Packages, and is built from source instead when it happens to sit
// next to this repository. That ordering is deliberate: working on both at once then needs no
// token and no publish step, while everyone else — CI included — resolves the published artifact,
// which is the path that has to keep working.
//
// Set -PlibcniFromPackages=true to ignore the directory and use the package even when it is there.
val libcni = file("../libcni-java")
val preferPackages = providers.gradleProperty("libcniFromPackages").orNull == "true"
if (libcni.isDirectory && !preferPackages) {
    includeBuild(libcni)
}
