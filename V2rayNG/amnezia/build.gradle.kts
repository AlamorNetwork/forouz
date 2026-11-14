import java.security.MessageDigest

plugins { alias(libs.plugins.android.library) }
// Root uses android.newDsl=false for legacy applicationVariants; remove this fallback after that migration.
@Suppress("DEPRECATION")
android {
    namespace = "org.amnezia.awg.tunnel"
    compileSdk = 37
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
}

val verifyPinnedNative = tasks.register("verifyPinnedNative") {
    val provenance = file("NATIVE-PROVENANCE.json")
    val nativeRoot = file("src/main/jniLibs")
    inputs.file(provenance)
    inputs.dir(nativeRoot)
    doLast {
        val metadata = groovy.json.JsonSlurper().parse(provenance) as Map<*, *>
        val hashes = metadata["native_sha256"] as Map<*, *>
        hashes.forEach { (abi, expected) ->
            val library = nativeRoot.resolve("$abi/libwg-go.so")
            check(library.isFile) { "Missing pinned AWG native library: $abi; run tools/prepare-amnezia.py" }
            val actual = MessageDigest.getInstance("SHA-256").digest(library.readBytes())
                .joinToString("") { "%02x".format(it) }
            check(actual == expected) { "AWG native library digest mismatch: $abi" }
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyPinnedNative) }
