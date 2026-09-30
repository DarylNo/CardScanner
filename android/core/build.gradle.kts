// Pure-JVM logic shared with the app and unit-tested WITHOUT Android:
// the port of phone.html's tuned detection, and the port of the server's
// card_detect.find_card_quad + the on-phone flattening contract.
//
// OpenCV is compileOnly against the desktop build (org.openpnp — same
// org.opencv.* Java API, bundles x86-64 natives for the JVM tests); at
// runtime on the phone the app supplies the Android AAR's classes.
plugins { alias(libs.plugins.kotlin.jvm) }

// Target Java 17 bytecode (same as :app) without pinning a toolchain, so the
// build runs on whatever JDK 17+ is installed (CI: 17; this box: 21).
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    compileOnly(libs.opencv.jvm)
    testImplementation(libs.opencv.jvm)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}

// Test helpers shared with :app's tests (the golden API replay runs against
// both the in-memory and the SQLite scan store).
sourceSets.getByName("test").kotlin.srcDir("src/testShared/kotlin")

tasks.test {
    // The differential test runs the REAL phone.html detection code under Node.
    systemProperty("phoneHtml", rootProject.file("../server/static/phone.html").absolutePath)
    systemProperty("nodeBin", System.getenv("NODE_BIN") ?: "node")
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
