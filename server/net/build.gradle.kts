// WebSocket transport (native Spring WebSocket, no STOMP) and JSON via Jackson 3.
dependencies {
    api(project(":core"))
    implementation("org.springframework:spring-websocket")
    implementation("tools.jackson.core:jackson-databind")
}
