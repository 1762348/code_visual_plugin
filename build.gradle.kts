plugins {
    java
    kotlin("jvm") version "2.0.0"
    id("org.jetbrains.intellij.platform") version "2.0.1"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create("IC", "2024.2")
        bundledPlugins("com.intellij.java")
        instrumentationTools()
    }
    //添加jetty轻量web服务器依赖
    implementation("org.eclipse.jetty:jetty-server:11.0.15")
    implementation("org.eclipse.jetty:jetty-servlet:11.0.15")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.github.javaparser:javaparser-symbol-solver-core:3.26.0")
    implementation("com.github.javaparser:javaparser-core:3.26.0")
}

// 固定 17：Kotlin 2.0.0 无法在 JDK 25 上编译（IllegalArgumentException: 25.0.2）
kotlin {
    jvmToolchain(17)
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    patchPluginXml {
        sinceBuild.set("242")
        untilBuild.set("243.*")
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}