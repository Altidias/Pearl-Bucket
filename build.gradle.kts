plugins {
    java
}

group = "dev.cevapi"
version = "0.1.9"

val pluginArtifactName = rootProject.name

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.70-stable")
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(25)
    }

    processResources {
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }

    register<Zip>("resourcePack") {
        group = "build"
        description = "Packages the optional PearlBucket client resource pack."
        archiveFileName.set("${pluginArtifactName}-resource-pack-${project.version}.zip")
        destinationDirectory.set(layout.buildDirectory.dir("resource-pack"))
        from(layout.projectDirectory.dir("resource-pack"))
        exclude("**/Thumbs.db", "**/.DS_Store", "*.zip")
    }

    jar {
        archiveBaseName.set(pluginArtifactName)
    }

    named("assemble") {
        dependsOn("resourcePack")
    }

    register<Zip>("releaseBundle") {
        group = "build"
        description = "Packages the plugin jar, resource pack, and docs for GitHub releases."
        dependsOn("jar", "resourcePack")
        archiveFileName.set("${pluginArtifactName}-${project.version}-release.zip")
        destinationDirectory.set(layout.buildDirectory.dir("distributions"))

        from(layout.buildDirectory.file("libs/${pluginArtifactName}-${project.version}.jar")) {
            into(pluginArtifactName)
        }
        from(layout.buildDirectory.file("resource-pack/${pluginArtifactName}-resource-pack-${project.version}.zip")) {
            into("${pluginArtifactName}/resource-pack")
        }
        from(layout.projectDirectory.file("README.md")) {
            into(pluginArtifactName)
        }
    }
}
