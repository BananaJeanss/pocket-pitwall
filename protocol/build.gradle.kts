plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    api(project(":core"))
    testImplementation(kotlin("test"))
}
