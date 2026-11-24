plugins {
    java
    kotlin("jvm") version "2.0.0"
    id("org.jetbrains.intellij.platform") version "2.0.1"
}

group = "org.example"
version = "0.1.0"

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
    testImplementation("junit:junit:4.13.2")
}

// 固定 17：Kotlin 2.0.0 无法在 JDK 25 上编译（IllegalArgumentException: 25.0.2）
kotlin {
    jvmToolchain(17)
}

// ---------------------------------------------------------------- 前端打包
// 把 Vue 的构建产物塞进插件资源目录（web/），这样：
//   1. 插件自己就能提供页面，用户不需要装 Node、也不需要跑 vite
//   2. 开发时仍可用 -Dcodecanvas.devServer=http://localhost:5173 走热更新

val frontendDir = layout.projectDirectory.dir("frontend")
val npmExecutable = if (System.getProperty("os.name").lowercase().contains("windows")) "npm.cmd" else "npm"

val npmInstall by tasks.registering(Exec::class) {
    group = "frontend"
    description = "安装前端依赖（仅在 node_modules 缺失时执行）"
    workingDir = frontendDir.asFile
    commandLine(npmExecutable, "ci")

    // 关键：npm ci 会先删掉 node_modules 再重装。
    // 如果此时用户正开着 vite dev server，原生二进制被占用 → EPERM。
    // 所以只在依赖缺失时才装；依赖变了请手动删 node_modules 或跑 npm install。
    onlyIf { !frontendDir.dir("node_modules").asFile.exists() }
}

val buildFrontend by tasks.registering(Exec::class) {
    group = "frontend"
    description = "构建 Vue 前端到 frontend/dist"
    dependsOn(npmInstall)
    workingDir = frontendDir.asFile
    commandLine(npmExecutable, "run", "build")
    inputs.dir(frontendDir.dir("src"))
    inputs.file(frontendDir.file("index.html"))
    inputs.file(frontendDir.file("vite.config.js"))
    inputs.file(frontendDir.file("package.json"))
    outputs.dir(frontendDir.dir("dist"))
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    processResources {
        dependsOn(buildFrontend)
        from(frontendDir.dir("dist").asFile) {
            into("web")
        }
    }

    patchPluginXml {
        sinceBuild.set("242")
        // 253.* 是为了能在 2025.3 上安装；更老/更新的版本装不上是预期行为
        untilBuild.set("253.*")
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
