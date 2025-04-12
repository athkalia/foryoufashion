plugins {
    kotlin("jvm") version "1.8.0"
    application
    kotlin("plugin.serialization") version "1.9.0" // Adjust version as necessary
}

repositories {
    mavenCentral()
}

tasks.test {
    useJUnitPlatform()
}

dependencies {
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.8.2")
    implementation("com.squareup.okhttp3:okhttp:4.9.3")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.3")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.13.3")
    implementation("org.jsoup:jsoup:1.13.1")
    implementation("com.opencsv:opencsv:5.5.2")
    implementation("javax.xml.bind:jaxb-api:2.3.1")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.8.0")
    implementation("org.jfree:jfreechart:1.5.3")
    implementation("org.apache.commons:commons-lang3:3.12.0")
    implementation("com.sun.mail:javax.mail:1.6.2")
    implementation("commons-net:commons-net:3.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.9.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.9.2")
}

tasks.withType<Jar> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "GenerateLocalProductFeedKt"
    }
    archiveFileName.set("foryoufashion.jar")
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.exists() }.map { if (it.isDirectory) it else zipTree(it) }
    })
}
