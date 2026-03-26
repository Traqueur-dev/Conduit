plugins {
    `java-library`
    `maven-publish`
}

allprojects {
    group = "fr.traqueur.conduit"
    version = "1.1.0"

    extra.set("classifier", System.getProperty("archive.classifier"))
    extra.set("sha", System.getProperty("github.sha"))

    rootProject.extra.properties["sha"]?.let { sha ->
        version = sha
    }

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        withSourcesJar()
        withJavadocJar()
    }

    tasks.withType<Test> {
        useJUnitPlatform()

        // Afficher les logs des tests
        testLogging {
            events("passed", "skipped", "failed", "standardOut", "standardError")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showExceptions = true
            showCauses = true
            showStackTraces = true
        }
    }

    dependencies {
        testImplementation("org.junit.jupiter:junit-jupiter:6.0.1")
        testImplementation("org.assertj:assertj-core:3.27.6")
        testImplementation("org.awaitility:awaitility:4.3.0")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.1")

    }

    publishing {
        repositories {
            maven {
                val repository = System.getProperty("repository.name", "snapshots")
                val repoType = repository.lowercase()

                name = "groupez${repository.replaceFirstChar { it.uppercase() }}"
                url = uri("https://repo.groupez.dev/$repoType")

                credentials {
                    username = findProperty("${name}Username") as String?
                        ?: System.getenv("MAVEN_USERNAME")
                    password = findProperty("${name}Password") as String?
                        ?: System.getenv("MAVEN_PASSWORD")
                }

                authentication {
                    create<BasicAuthentication>("basic")
                }
            }
        }

        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
                groupId = rootProject.group.toString()
                artifactId = project.name
                version = rootProject.version.toString()
            }
        }

    }

}

tasks.register("publishAll") {
    subprojects.forEach {
        dependsOn(it.tasks.publish)
    }
}