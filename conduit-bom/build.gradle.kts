plugins {
    `java-platform`
    `maven-publish`
}

javaPlatform {
    allowDependencies()
}

dependencies {
    constraints {
        // Conduit modules
        api("fr.traqueur.conduit:conduit-core:${rootProject.version}")
        api("fr.traqueur.conduit:conduit-redis:${rootProject.version}")
        api("fr.traqueur.conduit:conduit-rabbitmq:${rootProject.version}")

        // Core dependencies
        api("com.google.code.gson:gson:2.13.2")
        api("org.slf4j:slf4j-api:2.0.17")
        api("org.apache.commons:commons-compress:1.28.0")

        // Transport dependencies
        api("io.lettuce:lettuce-core:7.2.0.RELEASE")
        api("com.rabbitmq:amqp-client:5.28.0")
    }
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
            from(components["javaPlatform"])
            groupId = rootProject.group.toString()
            artifactId = "conduit-bom"
            version = rootProject.version.toString()
        }
    }
}