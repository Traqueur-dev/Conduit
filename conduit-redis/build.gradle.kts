dependencies {
    api(project(":conduit-core"))

    api("io.lettuce:lettuce-core:7.2.0.RELEASE")

    testImplementation(project(path = ":conduit-core", configuration = "testArtifacts"))
    testImplementation("org.testcontainers:testcontainers:2.0.2")
    testImplementation("org.testcontainers:junit-jupiter:1.21.3")
}