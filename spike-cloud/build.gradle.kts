plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
kotlin {
    jvmToolchain(17)
}
dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.aws.bedrockruntime)
    runtimeOnly("software.amazon.awssdk:netty-nio-client:${libs.versions.awsSdk.get()}")
    implementation("software.amazon.awssdk:netty-nio-client:${libs.versions.awsSdk.get()}")
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.slf4j.simple)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.ktor.server.test.host)
}
// Spike C-5 driver: ./gradlew :spike-cloud:c5 -Pscenario=basic|interrupt|inject|history|long
tasks.register<JavaExec>("c5") {
    group = "spike"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.callanswer.spikecloud.SonicSpikeKt")
    workingDir = rootProject.projectDir
    args(project.findProperty("scenario")?.toString() ?: "basic")
}
// Spike C-1 echo server: ./gradlew :spike-cloud:c1 -Pport=8080 (env ECHO_PUBLIC_HOST, ECHO_SAMPLE_RATE, ECHO_CHUNK_BYTES)
tasks.register<JavaExec>("c1") {
    group = "spike"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.callanswer.spikecloud.EchoServerKt")
    workingDir = rootProject.projectDir
    systemProperty("port", project.findProperty("port")?.toString() ?: "8080")
}
tasks.test {
    useJUnitPlatform()
}
