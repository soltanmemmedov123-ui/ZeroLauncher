// zero_agent — plain Java JAR (no Android deps), built as a java-library.
// The manifest Agent-Class / Premain-Class attributes are set here so the
// output JAR can be used directly as a -javaagent.
plugins {
    id("java-library")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

// Embed the IpcServer source so the agent can call IpcServer.get() at runtime.
// At runtime inside the game JVM, IpcServer is already loaded by the launcher's
// class loader — but the agent needs the class on its compile classpath.
// We reference it as a file dependency on the compiled launcher classes.
// (In practice the agent jar is loaded by the system class loader and shares
//  the same JVM as IpcServer, so no duplication occurs at runtime.)
dependencies {
    // IpcServer lives in the ZalithLauncher module — add it as a compile-only dep
    compileOnly(project(":ZalithLauncher"))
    implementation("org.ow2.asm:asm:9.6")
    implementation("org.ow2.asm:asm-commons:9.6")
}

tasks.jar {
    manifest {
        attributes(
            "Premain-Class"  to "com.movtery.zeroagent.ZeroAgent",
            "Agent-Class"    to "com.movtery.zeroagent.ZeroAgent",
            "Can-Redefine-Classes"   to "false",
            "Can-Retransform-Classes" to "false"
        )
    }
    // Fat jar — include all runtime deps (none currently, but future-proof)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
