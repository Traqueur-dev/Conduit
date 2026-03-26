dependencies {
    api(project(":conduit-core"))

    // Client RabbitMQ externe
    api("com.rabbitmq:amqp-client:5.28.0")

    // Testing - versions alignées via BOM
    testImplementation(project(path = ":conduit-core", configuration = "testArtifacts"))
    testImplementation("org.testcontainers:testcontainers:2.0.2")
    testImplementation("org.testcontainers:junit-jupiter:1.21.3")
    testImplementation("org.testcontainers:rabbitmq:1.21.3")
}
