plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // A2 (design note §2/§3, rule D2 — declare only on real reference):
    // AiError appears in :core:agent's PUBLIC API (AgentError.Provider,
    // carried by the public TurnFailed event), so :core:ai is `api`.
    // :core:engine types (BoltAction/ActionSafetyPolicy/CoreToolActions)
    // stay internal to the loop's gate, never in a public contract type,
    // so :core:engine is `implementation`.
    api(project(":core:ai"))
    implementation(project(":core:engine"))
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
