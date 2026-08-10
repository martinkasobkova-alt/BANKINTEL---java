plugins {
    java
    id("org.springframework.boot") version "3.4.4"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "cz.bankintel"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    implementation("org.apache.lucene:lucene-core:9.12.1")
    implementation("org.apache.lucene:lucene-analysis-common:9.12.1")
    implementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")
    implementation("ai.djl.huggingface:tokenizers:0.31.1")
    implementation("org.mongodb:mongodb-driver-sync:5.2.1")
    implementation("org.mongodb:mongodb-driver-core:5.2.1")
    implementation("org.mongodb:bson:5.2.1")

    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    implementation("org.springframework.security:spring-security-crypto")
    implementation("org.apache.poi:poi-ooxml:5.2.5")
    implementation("org.apache.pdfbox:pdfbox:2.0.32")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    developmentOnly("org.springframework.boot:spring-boot-devtools")
    compileOnly("io.zonky.test:embedded-postgres:2.0.7")
    runtimeOnly("io.zonky.test:embedded-postgres:2.0.7")
    compileOnly("io.zonky.test:embedded-database-spring-test:2.5.1")
    developmentOnly("io.zonky.test:embedded-database-spring-test:2.5.1")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("io.zonky.test:embedded-postgres:2.0.7")
    testImplementation("io.zonky.test:embedded-database-spring-test:2.5.1")
    testRuntimeOnly("com.h2database:h2")
    developmentOnly("com.h2database:h2")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:unchecked")
}

tasks.bootRun {
    systemProperty("spring.config.import", "optional:file:.env[.properties]")
    args("--spring.profiles.active=local")
    // BankIntelEnvEnvironmentPostProcessor loads backend-java/.env and optional local dev.local.env.
}
