plugins { kotlin("jvm") }

kotlin { jvmToolchain(17) }

// Plain self-test runner (src/test/.../EngineSelfTest.kt). Expects tools/xcheck/out/{bars,ref}.csv,
// produced by:  cd tools/xcheck && python3 ref.py && mkdir -p out && mv bars.csv ref.csv out/
val selfTest by tasks.registering(JavaExec::class) {
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.scanmaster.engine.EngineSelfTestKt")
    args(rootProject.file("tools/xcheck/out").absolutePath)
}
tasks.named("check") { dependsOn(selfTest) }
