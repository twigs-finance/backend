plugins {
    kotlin("jvm")
    `java-library`
}

val ktorVersion: String by rootProject.extra

dependencies {
    implementation(kotlin("stdlib"))
    api(project(":storage"))
    implementation("org.postgresql:postgresql:42.3.1")
    api("com.zaxxer:HikariCP:5.0.0")
    implementation("ch.qos.logback:logback-classic:1.2.8")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}