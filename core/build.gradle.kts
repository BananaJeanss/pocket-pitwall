plugins { `java-library` }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
tasks.register<JavaExec>("checkTelemetry") {
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.bananajeans.pitwall.core.TelemetryTest")
}
tasks.named("check") { dependsOn("checkTelemetry") }
