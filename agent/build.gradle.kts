plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.callanswer.agent.MainKt")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.aws.bedrockruntime)
    implementation(libs.aws.dynamodb)
    implementation(libs.aws.s3)
    implementation(libs.aws.secretsmanager)
    implementation(libs.aws.cloudwatch)
    implementation("software.amazon.awssdk:netty-nio-client:${libs.versions.awsSdk.get()}")
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.slf4j.simple)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}
tasks.register<JavaExec>("runAdminCli") {
    group = "application"
    description = "Runs the admin CLI (scripts/admin/callagent-admin)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.callanswer.agent.tenant.AdminCliKt")
}
