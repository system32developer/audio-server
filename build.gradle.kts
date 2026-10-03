plugins {
    kotlin("jvm") version "2.0.21"
    application
}

repositories { mavenCentral() }

kotlin { jvmToolchain(21) }

application {
    mainClass.set("audioserver.MainKt")
}
