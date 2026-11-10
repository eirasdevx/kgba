import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
}

application {
    // Reproductor de escritorio para probar el núcleo en el PC:
    //   ./gradlew :desktop:run --args="/ruta/juego.gba"
    mainClass.set("com.kgba.desktop.DesktopPlayerKt")
}

tasks.register<JavaExec>("headless") {
    group = "application"
    description = "Ejecuta una ROM sin ventana y guarda una captura (pruebas)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.kgba.desktop.HeadlessKt")
}
