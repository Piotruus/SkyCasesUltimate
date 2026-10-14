plugins {
    java
    id("com.gradleup.shadow") version "8.3.6"
}

group = "pl.skycases"
version = "1.0.0-1.21.4-candidate"

repositories { mavenCentral(); maven("https://repo.papermc.io/repository/maven-public/") }

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }
tasks.compileJava { options.encoding = "UTF-8" }
tasks.processResources { filteringCharset = "UTF-8" }
tasks.shadowJar { archiveClassifier.set(""); mergeServiceFiles() }
tasks.jar { enabled = false }
tasks.build { dependsOn(tasks.shadowJar) }
